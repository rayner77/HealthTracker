package com.inf2007.healthtracker.utilities.collectors

interface DataCollector {
    fun startObserving()
    fun stopObserving()
    fun collect()
}