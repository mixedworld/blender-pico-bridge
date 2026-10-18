package com.example.blenderpicobridge.p7f82b4c1.domain.model

/** Geometry is expressed in one named rigid reference, never a previous session's Stage. */
data class AlignmentSnapshot(val monitor: MonitorCalibration, val handles: MonitorHandleDraft,
    val stagedPose: InspectionPose? = null, val revision: String? = null)
data class SavedAlignment(val anchorId: String, val relative: AlignmentSnapshot)
data class AlignmentSaveRequest(val id: Int, val monitor: MonitorCalibration, val handles: MonitorHandleDraft)
