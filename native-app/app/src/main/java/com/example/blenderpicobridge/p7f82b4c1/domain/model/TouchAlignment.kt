package com.example.blenderpicobridge.p7f82b4c1.domain.model

enum class TouchingHand { RIGHT, LEFT }
data class TouchAlignmentRequest(val id: Int,val hand: TouchingHand,val board: CalibrationBoard)
enum class TouchAlignmentPhase { NONE, SETUP, CAPTURE, REVIEW, INVALID }
