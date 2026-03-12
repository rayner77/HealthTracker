package com.inf2007.healthtracker.utilities.collectors

interface DataCollector {
    fun collect()
    fun startObserving()
    fun stopObserving()
}