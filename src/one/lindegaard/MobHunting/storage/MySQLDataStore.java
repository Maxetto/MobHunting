package one.lindegaard.MobHunting.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import one.lindegaard.MobHunting.MobHunting;
import one.lindegaard.MobHunting.StatType;
import one.lindegaard.MobHunting.util.UUIDHelper;

public class MySQLDataStore extends DatabaseDataStore {

	// *******************************************************************************
	// SETUP / INITIALIZE
	// *******************************************************************************

	@Override
	protected Connection setupConnection() throws SQLException, DataStoreException {
		try {
			Class.forName("com.mysql.jdbc.Driver");
			return DriverManager.getConnection(
					"jdbc:mysql://" + MobHunting.getConfigManager().databaseHost + "/"
							+ MobHunting.getConfigManager().databaseName + "?autoReconnect=true",
					MobHunting.getConfigManager().databaseUsername,
					MobHunting.getConfigManager().databasePassword);
		} catch (ClassNotFoundException e) {
			throw new DataStoreException("MySQL not present on the classpath");
		}
	}

	@Override
	protected void openPreparedStatements(Connection connection, PreparedConnectionType preparedConnectionType)
			throws SQLException {
		switch (preparedConnectionType) {
		case SAVE_PLAYER_STATS:
			mSavePlayerStatsStatement = connection.prepareStatement(
					"INSERT IGNORE INTO mh_Daily(ID, PLAYER_ID) VALUES(DATE_FORMAT(NOW(), '%Y%j'),?);");
			break;
		case LOAD_ARCHIEVEMENTS:
			mLoadAchievementsStatement = connection
					.prepareStatement("SELECT ACHIEVEMENT, DATE, PROGRESS FROM mh_Achievements WHERE PLAYER_ID = ?;");
			break;
		case SAVE_ACHIEVEMENTS:
			mSaveAchievementStatement = connection.prepareStatement("REPLACE INTO mh_Achievements VALUES(?,?,?,?);");
			break;
		case GET1PLAYER:
			mGetPlayerStatement[0] = connection.prepareStatement("SELECT * FROM mh_Players WHERE UUID=?;");
			break;
		case GET2PLAYERS:
			mGetPlayerStatement[1] = connection.prepareStatement("SELECT * FROM mh_Players WHERE UUID IN (?,?);");
			break;
		case GET5PLAYERS:
			mGetPlayerStatement[2] = connection.prepareStatement("SELECT * FROM mh_Players WHERE UUID IN (?,?,?,?,?);");
			break;
		case GET10PLAYERS:
			mGetPlayerStatement[3] = connection
					.prepareStatement("SELECT * FROM mh_Players WHERE UUID IN (?,?,?,?,?,?,?,?,?,?);");
			break;
		case GET_PLAYER_UUID:
			mGetPlayerUUID = connection.prepareStatement("SELECT UUID FROM mh_Players WHERE NAME=?;");
			break;
		case UPDATE_PLAYER_NAME:
			mUpdatePlayerName = connection.prepareStatement("UPDATE mh_Players SET NAME=? WHERE UUID=?;");
			break;
		case UPDATE_PLAYER_SETTINGS:
			mUpdatePlayerSettings = connection
					.prepareStatement("UPDATE mh_Players SET LEARNING_MODE=?,MUTE_MODE=?" + " WHERE UUID=?;");
			break;
		case INSERT_PLAYER_DATA:
			mInsertPlayerData = connection.prepareStatement(
					"INSERT IGNORE INTO mh_Players " + "(UUID,NAME,LEARNING_MODE,MUTE_MODE) " + "VALUES(?,?,?,?);");
			break;
		case INSERT_PLAYER_SETTINGS:
			myAddPlayerStatement = connection.prepareStatement("INSERT IGNORE INTO mh_Players(UUID,NAME) VALUES(?,?);");
		case GET_PLAYER_SETTINGS:
			mGetPlayerDATA = connection.prepareStatement("SELECT * FROM mh_Players WHERE UUID=?;");
			break;
		default:
			break;
		}
	}

	// *******************************************************************************
	// LoadStats / SaveStats
	// *******************************************************************************

	@Override
	public List<StatStore> loadPlayerStats(StatType type, TimePeriod period, int count) throws DataStoreException {
		String id;
		switch (period) {
		case Day:
			id = "DATE_FORMAT(NOW(), '%Y%j')";
			break;
		case Week:
			id = "DATE_FORMAT(NOW(), '%Y%U')";
			break;
		case Month:
			id = "DATE_FORMAT(NOW(), '%Y%c')";
			break;
		case Year:
			id = "DATE_FORMAT(NOW(), '%Y')";
			break;
		default:
			id = null;
			break;
		}
		try {
			Statement statement = mConnection.createStatement();
			ResultSet results = statement
					.executeQuery("SELECT " + type.getDBColumn() + ", mh_Players.UUID, mh_Players.NAME from mh_"
							+ period.getTable() + " inner join mh_Players on mh_Players.PLAYER_ID=mh_"
							+ period.getTable() + ".PLAYER_ID" + (id != null ? " where ID=" + id : " ") + " order by "
							+ type.getDBColumn() + " desc limit " + count);
			ArrayList<StatStore> list = new ArrayList<StatStore>();

			while (results.next())
				list.add(new StatStore(type, Bukkit.getOfflinePlayer(UUID.fromString(results.getString(2))),
						results.getInt(1)));
			results.close();
			statement.close();
			return list;
		} catch (SQLException e) {
			throw new DataStoreException(e);
		}
	}

	@Override
	public void savePlayerStats(Set<StatStore> stats) throws DataStoreException {
		try {
			MobHunting.debug("Saving PlayerStats to Database.");

			HashSet<OfflinePlayer> names = new HashSet<OfflinePlayer>();
			for (StatStore stat : stats)
				names.add(stat.getPlayer());
			Map<UUID, Integer> ids = getPlayerIds(names);

			// Make sure the stats are available for each player
			openPreparedStatements(mConnection, PreparedConnectionType.SAVE_PLAYER_STATS);
			mSavePlayerStatsStatement.clearBatch();
			for (OfflinePlayer player : names) {
				mSavePlayerStatsStatement.setInt(1, ids.get(player.getUniqueId()));
				mSavePlayerStatsStatement.addBatch();
			}
			mSavePlayerStatsStatement.executeBatch();
			mSavePlayerStatsStatement.close();

			// Now add each of the stats
			Statement statement = mConnection.createStatement();
			for (StatStore stat : stats)
				statement.addBatch(String.format(
						"UPDATE mh_Daily SET %1$s = %1$s + %3$d WHERE ID = DATE_FORMAT(NOW(), '%%Y%%j') AND PLAYER_ID = %2$d;",
						stat.getType().getDBColumn(), ids.get(stat.getPlayer().getUniqueId()), stat.getAmount()));
			statement.executeBatch();
			statement.close();
			mConnection.commit();
			MobHunting.debug("Saved.", "");
		} catch (SQLException e) {
			rollback();
			throw new DataStoreException(e);
		}
	}

	// *******************************************************************************
	// DATABASE SETUP / MIGRATION
	// *******************************************************************************

	@Override
	protected void setupTables(Connection connection) throws SQLException {

		Statement create = connection.createStatement();

		// Prefix tables to mh_
		try {
			ResultSet rs = create.executeQuery("SELECT * from Daily LIMIT 0");
			rs.close();
			create.executeUpdate("RENAME TABLE Players TO mh_Players");
			create.executeUpdate("RENAME TABLE Daily TO mh_Daily");
			create.executeUpdate("RENAME TABLE Weekly TO mh_Weekly");
			create.executeUpdate("RENAME TABLE Monthly TO mh_Monthly");
			create.executeUpdate("RENAME TABLE Yearly TO mh_Yearly");
			create.executeUpdate("RENAME TABLE AllTime TO mh_AllTime");
			create.executeUpdate("RENAME TABLE Achievements TO mh_Achievements");

			create.executeUpdate("DROP TRIGGER IF EXISTS DailyInsert");
			create.executeUpdate("DROP TRIGGER IF EXISTS DailyUpdate");

		} catch (SQLException e) {
		}

		// Create new empty tables if they do not exist
		String lm = MobHunting.getConfigManager().learningMode ? "1" : "0";
		create.executeUpdate(
				"CREATE TABLE IF NOT EXISTS mh_Players (UUID CHAR(40) PRIMARY KEY, NAME CHAR(20), PLAYER_ID INTEGER NOT NULL AUTO_INCREMENT, "
						+ "KEY PLAYER_ID (PLAYER_ID), LEARNING_MODE INTEGER NOT NULL DEFAULT " + lm
						+ ", MUTE_MODE INTEGER NOT NULL DEFAULT 0)");
		String dataString = "";
		for (StatType type : StatType.values())
			dataString += ", " + type.getDBColumn() + " INTEGER NOT NULL DEFAULT 0";
		create.executeUpdate(
				"CREATE TABLE IF NOT EXISTS mh_Daily (ID CHAR(7) NOT NULL, PLAYER_ID INTEGER REFERENCES mh_Players(PLAYER_ID) ON DELETE CASCADE"
						+ dataString + ", PRIMARY KEY(ID, PLAYER_ID))");
		create.executeUpdate(
				"CREATE TABLE IF NOT EXISTS mh_Weekly (ID CHAR(6) NOT NULL, PLAYER_ID INTEGER REFERENCES mh_Players(PLAYER_ID) ON DELETE CASCADE"
						+ dataString + ", PRIMARY KEY(ID, PLAYER_ID))");
		create.executeUpdate(
				"CREATE TABLE IF NOT EXISTS mh_Monthly (ID CHAR(6) NOT NULL, PLAYER_ID INTEGER REFERENCES mh_Players(PLAYER_ID) ON DELETE CASCADE"
						+ dataString + ", PRIMARY KEY(ID, PLAYER_ID))");
		create.executeUpdate(
				"CREATE TABLE IF NOT EXISTS mh_Yearly (ID CHAR(4) NOT NULL, PLAYER_ID INTEGER REFERENCES mh_Players(PLAYER_ID) ON DELETE CASCADE"
						+ dataString + ", PRIMARY KEY(ID, PLAYER_ID))");
		create.executeUpdate(
				"CREATE TABLE IF NOT EXISTS mh_AllTime (PLAYER_ID INTEGER REFERENCES mh_Players(PLAYER_ID) ON DELETE CASCADE"
						+ dataString + ", PRIMARY KEY(PLAYER_ID))");
		create.executeUpdate(
				"CREATE TABLE IF NOT EXISTS mh_Achievements (PLAYER_ID INTEGER REFERENCES mh_Players(PLAYER_ID) ON DELETE CASCADE, ACHIEVEMENT VARCHAR(64) NOT NULL, DATE DATETIME NOT NULL, PROGRESS INTEGER NOT NULL, PRIMARY KEY(PLAYER_ID, ACHIEVEMENT))");

		// Setup Database triggers
		setupTrigger(connection);

		create.close();
		connection.commit();

		performUUIDMigrate(connection);
		performAddNewMobs(connection);
	}

	private void setupTrigger(Connection connection) throws SQLException {

		Statement create = connection.createStatement();

		// Workaround for no create trigger if not exists
		try {
			create.executeUpdate(
					"create trigger mh_DailyInsert after insert on mh_Daily for each row begin insert ignore into mh_Weekly(ID, PLAYER_ID) values(DATE_FORMAT(NOW(), '%Y%U'), NEW.PLAYER_ID); insert ignore into mh_Monthly(ID, PLAYER_ID) values(DATE_FORMAT(NOW(), '%Y%c'), NEW.PLAYER_ID); insert ignore into mh_Yearly(ID, PLAYER_ID) values(DATE_FORMAT(NOW(), '%Y'), NEW.PLAYER_ID); insert ignore into mh_AllTime(PLAYER_ID) values(NEW.PLAYER_ID); end"); //$NON-NLS-1$

			// Create the cascade update trigger. It will allow us to only
			// modify the Daily table, and the rest will happen automatically
			StringBuilder updateStringBuilder = new StringBuilder();

			for (StatType type : StatType.values()) {
				if (updateStringBuilder.length() != 0)
					updateStringBuilder.append(", ");

				updateStringBuilder.append(String.format("%s = (%1$s + (NEW.%1$s - OLD.%1$s)) ", type.getDBColumn()));
			}

			String updateString = updateStringBuilder.toString();

			StringBuilder updateTrigger = new StringBuilder();
			updateTrigger.append("create trigger mh_DailyUpdate after update on mh_Daily for each row begin ");

			// Weekly
			updateTrigger.append(" update mh_Weekly set ");
			updateTrigger.append(updateString);
			updateTrigger.append(" where ID=DATE_FORMAT(NOW(), '%Y%U') AND PLAYER_ID=New.PLAYER_ID;");

			// Monthly
			updateTrigger.append(" update mh_Monthly set ");
			updateTrigger.append(updateString);
			updateTrigger.append(" where ID=DATE_FORMAT(NOW(), '%Y%c') AND PLAYER_ID=New.PLAYER_ID;");

			// Yearly
			updateTrigger.append(" update mh_Yearly set ");
			updateTrigger.append(updateString);
			updateTrigger.append(" where ID=DATE_FORMAT(NOW(), '%Y') AND PLAYER_ID=New.PLAYER_ID;");

			// AllTime
			updateTrigger.append(" update mh_AllTime set ");
			updateTrigger.append(updateString);
			updateTrigger.append(" where PLAYER_ID=New.PLAYER_ID;");

			updateTrigger.append("END");

			create.executeUpdate(updateTrigger.toString());
		} catch (SQLException e) {
			// Do Nothing
		}

		create.close();
		connection.commit();
	}

	private void performUUIDMigrate(Connection connection) throws SQLException {
		Statement statement = connection.createStatement();
		try {
			ResultSet rs = statement.executeQuery("SELECT UUID from `mh_Players` LIMIT 0");
			rs.close();
			statement.close();
			return; // UUIDs are in place

		} catch (SQLException e) {
		}

		System.out.println("[MobHunting] Migrating MobHunting Database Player ID to Player UUID.");

		// Add missing columns
		// Statement statement = connection.createStatement();
		statement.executeUpdate(
				"alter table `mh_Players` add column `UUID` CHAR(40) default '**UNSPEC**' NOT NULL first");

		// Get UUID and update table
		ResultSet rs = statement.executeQuery("select `NAME` from `mh_Players`");
		UUIDHelper.initialize();

		PreparedStatement insert = connection.prepareStatement("update `mh_Players` set `UUID`=? where `NAME`=?");
		StringBuilder failString = new StringBuilder();
		int failCount = 0;
		while (rs.next()) {
			String player = rs.getString(1);
			UUID id = UUIDHelper.getKnown(player);
			if (id != null) {
				insert.setString(1, id.toString());
				insert.setString(2, player);
				insert.addBatch();
			} else {
				if (failString.length() != 0)
					failString.append(", ");
				failString.append(player);
				++failCount;
			}
		}

		UUIDHelper.clearCache();

		rs.close();

		if (failCount > 0) {
			System.err.println("[MobHunting] " + failCount + " accounts failed to convert:");
			System.err.println("[MobHunting] " + failString.toString());
		}

		insert.executeBatch();
		insert.close();

		int modified = statement.executeUpdate("delete from `mh_Players` where `UUID`='**UNSPEC**'");
		System.out.println("[MobHunting]" + modified + " players were removed due to missing UUIDs");

		statement.executeUpdate("alter table `mh_Players` drop primary key");
		statement.executeUpdate("alter table `mh_Players` modify `UUID` CHAR(40) NOT NULL PRIMARY KEY first");

		System.out.println("[MobHunting] Player UUID migration complete.");

		statement.close();
		connection.commit();
	}

	private void performAddNewMobs(Connection connection) throws SQLException {
		Statement statement = connection.createStatement();
		try {
			ResultSet rs = statement.executeQuery("SELECT Bat_kill from `mh_Daily` LIMIT 0");
			rs.close();
		} catch (SQLException e) {

			System.out.println("[MobHunting] Adding Passive Mobs to MobHunting Database ");

			statement.executeUpdate("alter table `mh_Daily` add column `Bat_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Bat_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Bat_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Bat_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Bat_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Bat_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Bat_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Bat_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Bat_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Bat_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `Chicken_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Chicken_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Chicken_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Chicken_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Chicken_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Chicken_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Chicken_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Chicken_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Chicken_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Chicken_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `Cow_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Cow_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Cow_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Cow_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Cow_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Cow_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Cow_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Cow_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Cow_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Cow_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `Horse_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Horse_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Horse_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Horse_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Horse_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Horse_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Horse_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Horse_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Horse_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Horse_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `MushroomCow_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Daily` add column `MushroomCow_assist`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_Weekly` add column `MushroomCow_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Weekly` add column `MushroomCow_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Monthly` add column `MushroomCow_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Monthly` add column `MushroomCow_assist`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_Yearly` add column `MushroomCow_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Yearly` add column `MushroomCow_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_AllTime` add column `MushroomCow_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_AllTime` add column `MushroomCow_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `Ocelot_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Ocelot_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Ocelot_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Ocelot_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Ocelot_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Ocelot_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Ocelot_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Ocelot_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Ocelot_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Ocelot_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `Pig_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Pig_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Pig_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Pig_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Pig_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Pig_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Pig_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Pig_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Pig_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Pig_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate(
					"alter table `mh_Daily` add column `PassiveRabbit_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Daily` add column `PassiveRabbit_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Weekly` add column `PassiveRabbit_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Weekly` add column `PassiveRabbit_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Monthly` add column `PassiveRabbit_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Monthly` add column `PassiveRabbit_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Yearly` add column `PassiveRabbit_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Yearly` add column `PassiveRabbit_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_AllTime` add column `PassiveRabbit_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_AllTime` add column `PassiveRabbit_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `Sheep_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Sheep_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Sheep_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Sheep_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Sheep_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Sheep_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Sheep_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Sheep_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Sheep_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Sheep_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `Snowman_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Snowman_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Snowman_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Snowman_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Snowman_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Snowman_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Snowman_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Snowman_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Snowman_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Snowman_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `Squid_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Squid_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Squid_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Squid_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Squid_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Squid_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Squid_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Squid_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Squid_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Squid_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `Villager_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Villager_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Villager_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Villager_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Villager_kill`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_Monthly` add column `Villager_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Villager_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Villager_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Villager_kill`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_AllTime` add column `Villager_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `Wolf_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Wolf_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Wolf_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Wolf_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Wolf_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Wolf_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Wolf_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Wolf_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Wolf_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Wolf_assist`  INTEGER NOT NULL DEFAULT 0");

			System.out.println("[MobHunting] Adding passive mobs complete.");

		}
		try {
			ResultSet rs = statement.executeQuery("SELECT EnderDragon_kill from `mh_Daily` LIMIT 0");
			rs.close();
		} catch (SQLException e) {

			System.out.println("[MobHunting] Adding EnderDragon to MobHunting Database.");

			statement.executeUpdate("alter table `mh_Daily` add column `EnderDragon_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Daily` add column `EnderDragon_assist`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_Weekly` add column `EnderDragon_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Weekly` add column `EnderDragon_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Monthly` add column `EnderDragon_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Monthly` add column `EnderDragon_assist`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_Yearly` add column `EnderDragon_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Yearly` add column `EnderDragon_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_AllTime` add column `EnderDragon_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_AllTime` add column `EnderDragon_assist`  INTEGER NOT NULL DEFAULT 0");

			System.out.println("[MobHunting] Adding EnderDragon complete.");

		}
		try {
			ResultSet rs = statement.executeQuery("SELECT IronGolem_kill from `mh_Daily` LIMIT 0");
			rs.close();
		} catch (SQLException e) {

			System.out.println("[MobHunting] Adding IronGolem to MobHunting Database.");

			statement.executeUpdate("alter table `mh_Daily` add column `IronGolem_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `IronGolem_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `IronGolem_kill`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_Weekly` add column `IronGolem_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `IronGolem_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Monthly` add column `IronGolem_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `IronGolem_kill`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_Yearly` add column `IronGolem_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `IronGolem_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_AllTime` add column `IronGolem_assist`  INTEGER NOT NULL DEFAULT 0");

			System.out.println("[MobHunting] Adding IronGolem complete.");

		}
		try {
			ResultSet rs = statement.executeQuery("SELECT PvpPlayer_kill from `mh_Daily` LIMIT 0");
			rs.close();
		} catch (SQLException e) {

			System.out.println("[MobHunting] Adding new PvpPlayer to MobHunting Database.");

			statement.executeUpdate("alter table `mh_Daily` add column `PvpPlayer_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `PvpPlayer_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `PvpPlayer_kill`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_Weekly` add column `PvpPlayer_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `PvpPlayer_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Monthly` add column `PvpPlayer_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `PvpPlayer_kill`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_Yearly` add column `PvpPlayer_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `PvpPlayer_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_AllTime` add column `PvpPlayer_assist`  INTEGER NOT NULL DEFAULT 0");

			System.out.println("[MobHunting] Adding new PvpPlayer complete.");

		}
		try {
			ResultSet rs = statement.executeQuery("SELECT Giant_kill from `mh_Daily` LIMIT 0");
			rs.close();

		} catch (SQLException e) {

			System.out.println("[MobHunting] Adding new 1.8 Mobs to MobHunting Database.");

			statement.executeUpdate("alter table `mh_Daily` add column `Endermite_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Endermite_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Endermite_kill`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_Weekly` add column `Endermite_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Endermite_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Monthly` add column `Endermite_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Endermite_kill`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_Yearly` add column `Endermite_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Endermite_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_AllTime` add column `Endermite_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `Giant_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Giant_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Giant_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Giant_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Giant_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Giant_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Giant_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Giant_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Giant_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Giant_assist`  INTEGER NOT NULL DEFAULT 0");

			statement.executeUpdate("alter table `mh_Daily` add column `Guardian_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Guardian_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_eekly` add column `Guardian_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Guardian_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Guardian_kill`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_Monthly` add column `Guardian_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Guardian_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Guardian_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Guardian_kill`  INTEGER NOT NULL DEFAULT 0");
			statement
					.executeUpdate("alter table `mh_AllTime` add column `Guardian_assist`  INTEGER NOT NULL DEFAULT 0");

			statement
					.executeUpdate("alter table `mh_Daily` add column `KillerRabbit_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Daily` add column `KillerRabbit_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Weekly` add column `KillerRabbit_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Weekly` add column `KillerRabbit_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Monthly` add column `KillerRabbit_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Monthly` add column `KillerRabbit_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Yearly` add column `KillerRabbit_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_Yearly` add column `KillerRabbit_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_AllTime` add column `KillerRabbit_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate(
					"alter table `mh_AllTime` add column `KillerRabbit_assist`  INTEGER NOT NULL DEFAULT 0");

			System.out.println("[MobHunting] Adding new 1.8 Mobs complete.");

		}

		try {
			ResultSet rs = statement.executeQuery("SELECT Shulker_kill from `mh_Daily` LIMIT 0");
			rs.close();

		} catch (SQLException e) {

			System.out.println("[MobHunting] Adding new 1.9 Mobs (Shulker) to MobHunting Database.");

			statement.executeUpdate("alter table `mh_Daily` add column `Shulker_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Daily` add column `Shulker_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Shulker_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Weekly` add column `Shulker_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Shulker_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Monthly` add column `Shulker_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Shulker_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_Yearly` add column `Shulker_assist`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Shulker_kill`  INTEGER NOT NULL DEFAULT 0");
			statement.executeUpdate("alter table `mh_AllTime` add column `Shulker_assist`  INTEGER NOT NULL DEFAULT 0");

			System.out.println("[MobHunting] Adding new 1.9 Mobs (Shulker) complete.");

		}
		try {
			ResultSet rs = statement.executeQuery("SELECT LEARNING_MODE from mh_Players LIMIT 0");
			rs.close();
		} catch (SQLException e) {
			System.out.println("[MobHunting] Adding new Player leaning mode to MobHunting Database.");
			String lm = MobHunting.getConfigManager().learningMode ? "1" : "0";
			statement.executeUpdate(
					"alter table `mh_Players` add column `LEARNING_MODE` INTEGER NOT NULL DEFAULT " + lm);
		}

		try {
			ResultSet rs = statement.executeQuery("SELECT MUTE_MODE from mh_Players LIMIT 0");
			rs.close();
		} catch (SQLException e) {
			System.out.println("[MobHunting] Adding new Player mute mode to MobHunting Database.");
			statement.executeUpdate("alter table `mh_Players` add column `MUTE_MODE` INTEGER NOT NULL DEFAULT 0");
		}

		System.out.println("[MobHunting] Updating database triggers.");
		statement.executeUpdate("DROP TRIGGER IF EXISTS `mh_DailyInsert`");
		statement.executeUpdate("DROP TRIGGER IF EXISTS `mh_DailyUpdate`");
		setupTrigger(connection);

		statement.close();
		connection.commit();
	}

}
