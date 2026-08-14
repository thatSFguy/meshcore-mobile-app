package io.github.thatsfguy.meshcore.android.storage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The database version and its migrations, checked against each other.
 *
 * There is no destructive fallback anywhere in this builder — deliberately,
 * because Room's would delete the user's history on an unexpected schema
 * version. The cost of that choice is that a version bumped without a
 * migration is a hard failure to open the database on every device that
 * already has one. This catches it here instead, without a device.
 *
 * A real migration test needs instrumentation (`MigrationTestHelper`),
 * which cannot run in this environment. These are the two mistakes that
 * are cheap to make and cheap to catch: a missing migration, and a
 * migration that does not mention the column the entity gained.
 */
class SchemaMigrationTest {

    private val dbSource = File(
        "src/main/kotlin/io/github/thatsfguy/meshcore/android/storage/MeshCoreDatabase.kt",
    ).readText()

    private val version =
        Regex("""version\s*=\s*(\d+)""").find(dbSource)!!.groupValues[1].toInt()

    @Test
    fun `every version step has a migration and it is registered`() {
        val registered = dbSource.substringAfter(".addMigrations(").substringBefore(")")
        for (from in 1 until version) {
            val name = "MIGRATION_${from}_${from + 1}"
            assertTrue(
                "no $name — bumping the version without one bricks every existing install",
                dbSource.contains("private val $name = object : Migration($from, ${from + 1})"),
            )
            assertTrue("$name is defined but never registered", registered.contains(name))
        }
    }

    @Test
    fun `the exported schema for the current version exists`() {
        val schema = File(
            "schemas/io.github.thatsfguy.meshcore.android.storage.MeshCoreDatabase/$version.json",
        )
        assertTrue("no exported schema for version $version", schema.exists())
    }

    @Test
    fun `update mode is a column of its own and not read off the address`() {
        // The defect this guards: "is in update mode" was derived from
        // `otaAddress != null`. A BLE address is hardware — it survives a
        // reboot, an update and a reflash over USB — so nothing ever
        // cleared it, and the claim became permanent. The state needs its
        // own column with named transitions, and the address needs to be
        // left alone.
        val schema = File(
            "schemas/io.github.thatsfguy.meshcore.android.storage.MeshCoreDatabase/$version.json",
        ).readText()
        assertTrue(
            "contacts has no updateModeSince column",
            schema.contains("\"columnName\": \"updateModeSince\""),
        )
        assertTrue(
            "the address column was dropped — it is hardware and must outlive the state",
            schema.contains("\"columnName\": \"otaAddress\""),
        )
        assertTrue(
            "no migration adds updateModeSince",
            dbSource.contains("ADD COLUMN `updateModeSince`"),
        )
    }

    @Test
    fun `a persisted start-ota reply is consumed against a watermark`() {
        // The same defect one table along. The console thread is
        // persisted, so `OK - mac: …` is a row that never goes away and
        // is re-read on every render — setting the flag from its presence
        // would be exactly as permanent as setting it from the address,
        // and would undo the operator's correction on the next visit.
        val schema = File(
            "schemas/io.github.thatsfguy.meshcore.android.storage.MeshCoreDatabase/$version.json",
        ).readText()
        assertTrue(
            "contacts has no otaReplyHandledAt watermark",
            schema.contains("\"columnName\": \"otaReplyHandledAt\""),
        )
        assertTrue(
            "no migration adds otaReplyHandledAt",
            dbSource.contains("ADD COLUMN `otaReplyHandledAt`"),
        )

        val panel = File(
            "src/main/kotlin/io/github/thatsfguy/meshcore/android/ui/screens/" +
                "RepeaterFirmwarePanel.kt",
        ).readText()
        // The comparison itself lives in `OtaEvidence` now, where
        // `OtaEntryTest` can put hostile threads through it. What is
        // pinned here is that the panel still hands it the watermark —
        // and that the watermark comes from a loaded row, never from a
        // default standing in for one. `dbContacts` starts empty, so a
        // `?: 0L` here meant the first composition consumed a reply from
        // days ago as if it had just arrived.
        assertTrue(
            "the panel sets update mode without checking the watermark",
            panel.contains("OtaEvidence.freshAdvertisingAddress(rows, it.otaReplyHandledAt)"),
        )
        assertTrue(
            "the watermark is defaulted rather than waited for",
            panel.contains("storedContact?.let { OtaEvidence.freshAdvertisingAddress"),
        )
        assertTrue(
            "the panel derives update mode from the reply rather than the flag",
            panel.contains("flaggedInUpdateMode = (storedContact?.updateModeSince ?: 0L) > 0L"),
        )
    }
}
