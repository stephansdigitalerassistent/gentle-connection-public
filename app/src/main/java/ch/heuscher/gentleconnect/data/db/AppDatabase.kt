package ch.heuscher.gentleconnect.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ThreemaContactEntity::class,
        ImportedMessageEntity::class,
        MailEntity::class,
        ThreemaMatchEntity::class,
        StoredMessageEntity::class,
        ThreemaGroupEntity::class,
        ThreemaGroupMemberEntity::class,
        CaregiverEventEntity::class
    ],
    version = 16,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun threemaContactDao(): ThreemaContactDao
    abstract fun importedMessageDao(): ImportedMessageDao
    abstract fun mailDao(): MailDao
    abstract fun threemaMatchDao(): ThreemaMatchDao
    abstract fun messageDao(): MessageDao
    abstract fun threemaGroupDao(): ThreemaGroupDao
    abstract fun caregiverEventDao(): CaregiverEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Adds the mails table.
         *
         * This is written out by hand rather than left to `fallbackToDestructiveMigration`,
         * which is still configured below as a last resort. Falling back would drop every
         * table — including the contacts and message history imported from her Threema backup,
         * which came off a file and a password that nobody is going to find again.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mails` (" +
                        "`id` TEXT NOT NULL, " +
                        "`senderName` TEXT NOT NULL, " +
                        "`senderAddress` TEXT NOT NULL, " +
                        "`subject` TEXT NOT NULL, " +
                        "`snippet` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`isRead` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `mails` ADD COLUMN `body` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `mails` ADD COLUMN `messageId` TEXT")
                db.execSQL("ALTER TABLE `mails` ADD COLUMN `uid` INTEGER")
                db.execSQL("ALTER TABLE `mails` ADD COLUMN `uidValidity` INTEGER")
                db.execSQL("ALTER TABLE `mails` ADD COLUMN `isVisible` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** Adds the table holding phone numbers the Threema directory recognised. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `threema_matches` (" +
                        "`phoneNumber` TEXT NOT NULL, " +
                        "`identity` TEXT NOT NULL, " +
                        "`matchedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`phoneNumber`))"
                )
            }
        }

        /**
         * Adds the table holding her conversations — everything she has sent and received.
         *
         * Written out by hand for the usual reason, and more so here than for any table before it:
         * `fallbackToDestructiveMigration` would drop this one, and for messages she sent and for
         * Threema messages she received there is nothing behind it to read back from. This table is
         * the only copy.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages` (" +
                        "`id` TEXT NOT NULL, " +
                        "`conversationId` TEXT NOT NULL, " +
                        "`direction` TEXT NOT NULL, " +
                        "`counterpartId` TEXT, " +
                        "`counterpartName` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`isRead` INTEGER NOT NULL, " +
                        "`sendState` TEXT, " +
                        "`transport` TEXT NOT NULL, " +
                        "`channel` TEXT NOT NULL, " +
                        "`groupCreator` TEXT, " +
                        "`groupId` TEXT, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_conversationId` " +
                        "ON `messages` (`conversationId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_timestamp` " +
                        "ON `messages` (`timestamp`)"
                )
            }
        }

        /**
         * Adds the two tables that say who is in a group and what it is called.
         *
         * Threema offers no roster and no lookup for either — membership is knowledge accumulated
         * by listening to control messages, so losing these tables means losing it until the
         * creator happens to send a setup message again, which may be never.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `threema_groups` (" +
                        "`creatorIdentity` TEXT NOT NULL, " +
                        "`groupId` TEXT NOT NULL, " +
                        "`name` TEXT, " +
                        "`lastSetupAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`creatorIdentity`, `groupId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `threema_group_members` (" +
                        "`creatorIdentity` TEXT NOT NULL, " +
                        "`groupId` TEXT NOT NULL, " +
                        "`identity` TEXT NOT NULL, " +
                        "PRIMARY KEY(`creatorIdentity`, `groupId`, `identity`))"
                )
            }
        }

        /**
         * Adds the caregiver's error log.
         *
         * The one table here that may be dropped without anybody losing anything they cannot get
         * back: it holds reports of faults, not her messages or her contacts. It is still written
         * out by hand, because reaching `fallbackToDestructiveMigration` would take the tables
         * either side of it with it.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    // `INTEGER PRIMARY KEY AUTOINCREMENT` spelled the way Room spells it for an
                    // autoGenerate key, so an upgraded phone ends up with the same table as a
                    // freshly installed one rather than one that merely passes validation.
                    "CREATE TABLE IF NOT EXISTS `caregiver_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL, " +
                        "`subject` TEXT, " +
                        "`count` INTEGER NOT NULL, " +
                        "`total` INTEGER NOT NULL, " +
                        "`occurrences` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Adds the photograph a message came with.
         *
         * Nullable, with no default, because every message that already exists genuinely has no
         * photograph: the bridge only started fetching them at this version. Written out rather
         * than left to `fallbackToDestructiveMigration`, which at this point would throw away the
         * four years of WhatsApp history the phone has just imported.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `photoPath` TEXT")
            }
        }

        /**
         * Adds the voice message a message came with.
         *
         * Same shape as the photograph column before it, and written out for the same reason:
         * reaching `fallbackToDestructiveMigration` here would throw away the four years of
         * WhatsApp history the phone imported.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `audioPath` TEXT")
            }
        }

        /**
         * Adds the reaction somebody put on a message.
         *
         * The model has carried a `reaction` since long before this table existed; the table
         * never had anywhere to put it, so every reaction was dropped on the way to disk and
         * lived only as long as the process. Nullable and undefaulted, because no message that
         * already exists has one that was ever saved.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `reaction` TEXT")
            }
        }

        /**
         * Adds the messenger's own name for a message she sent.
         *
         * Nullable and undefaulted: no message sent before this version has one, and there is no
         * way to work out after the fact what Threema called it.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `wireMessageId` TEXT")
            }
        }

        /**
         * Adds the film that comes with a video message, beside the still it already had.
         *
         * Written out rather than left to the destructive fallback for the reason given on
         * [MIGRATION_1_2]: dropping the tables to add one nullable column would throw away a
         * message history that came off a backup file nobody can produce a second time.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `videoPath` TEXT")
            }
        }

        /**
         * Adds the four columns carrying a quoted message: the id of what is being answered,
         * the carried copy of its sender and words where the transport provides them, and
         * whether the quoted message was one she sent herself.
         *
         * Nullable and undefaulted (except quotedIsHers which defaults to 0): no message stored
         * before this version has a quote, and Threema messages have only the id.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `quotedId` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `quotedSenderName` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `quotedText` TEXT")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `quotedIsHers` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds the pictures and the paper that came with a letter.
         *
         * Defaulted to the empty string rather than left nullable, because "no attachments" and
         * "not looked at yet" are the same thing here: every letter already stored was fetched by
         * a version that threw its files away, and they are never fetched a second time.
         *
         * Written out by hand for the reason given on [MIGRATION_1_2]. Reaching the destructive
         * fallback to add one column would take her whole message history with it.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `mails` ADD COLUMN `attachments` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `receivedAt` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Adds the group's own photograph.
         *
         * A group row is the one row in this app that has never had a face on it, deliberately:
         * see MessagePortrait, which returns null for a group message because putting the last
         * speaker's portrait on a row labelled "Hartmanns" says the family is him. The answer was
         * always a picture of the group itself, and a Threema backup has carried one all along —
         * `group_avatar_<row number>`, which nothing read.
         *
         * Nullable and undefaulted: every group already in this table genuinely has no picture,
         * because until now there was nowhere to put one.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `threema_groups` ADD COLUMN `photoPath` TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gentle_connection.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16
                    )
                    // No fallbackToDestructiveMigration(): a database this app cannot migrate
                    // must fail loudly rather than silently drop four years of her messages.
                    // Every new schema version therefore requires a migration written by hand,
                    // like the thirteen above it.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
