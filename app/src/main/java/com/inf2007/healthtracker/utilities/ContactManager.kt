package com.inf2007.healthtracker.utilities

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract

fun updateContactPhoneNumber(context: Context, contactName: String, newNumber: String) {
    val ops = ArrayList<ContentProviderOperation>()

    // Selection criteria: Find the data row that belongs to a person with this name
    // and is a phone number type
    val selection = "${ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME} = ? AND " +
            "${ContactsContract.Data.MIMETYPE} = ?"

    val selectionArgs = arrayOf(contactName, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)

    ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
        .withSelection(selection, selectionArgs)
        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, newNumber)
        .build())

    try {
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}