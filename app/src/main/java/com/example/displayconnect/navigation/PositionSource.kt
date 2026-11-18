package com.example.displayconnect.navigation

import kotlinx.coroutines.flow.Flow

/** Origen de posicion intercambiable: GPS del telefono (LocationTracker) o
 *  receptor GNSS de la placa Hecate (HecateGnssSource). */
interface PositionSource {
    fun locationFlow(intervalMs: Long): Flow<LocationUpdate>
}
