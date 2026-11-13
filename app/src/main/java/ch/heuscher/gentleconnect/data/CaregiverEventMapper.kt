package ch.heuscher.gentleconnect.data

import ch.heuscher.gentleconnect.data.db.CaregiverEventEntity
import ch.heuscher.gentleconnect.model.CaregiverEvent
import ch.heuscher.gentleconnect.model.CaregiverEventKind

/**
 * The caregiver's log across the database boundary.
 *
 * Pure both ways, so the round trip is testable — the storage layer around it is not.
 */
object CaregiverEventMapper {

    fun toRow(event: CaregiverEvent): CaregiverEventEntity = CaregiverEventEntity(
        kind = event.kind.name,
        at = event.at,
        subject = event.subject,
        count = event.count,
        total = event.total,
        occurrences = event.occurrences
    )

    /**
     * Reads a row back, or nothing.
     *
     * A row whose kind this version of the app does not know is dropped rather than shown. Unlike
     * a message — where a row from a later version degrades to something still readable, because
     * the database is the only copy of what she was told — a fault we cannot name is a line that
     * would have to render as blank or as "unknown problem", which is worse than absent on a
     * screen somebody is reading to work out what to fix.
     */
    fun fromRow(row: CaregiverEventEntity): CaregiverEvent? {
        val kind = CaregiverEventKind.entries.firstOrNull { it.name == row.kind } ?: return null
        return CaregiverEvent(
            kind = kind,
            at = row.at,
            subject = row.subject,
            count = row.count,
            total = row.total,
            occurrences = row.occurrences.coerceAtLeast(1)
        )
    }
}
