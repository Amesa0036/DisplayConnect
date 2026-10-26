package com.example.displayconnect.network

/** Reported by the connected board, not inferred from its advertised name. */
data class BoardInfo(val id: String, val name: String, val address: String)
