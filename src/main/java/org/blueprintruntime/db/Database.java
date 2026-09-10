package org.blueprintruntime.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Opens JDBC connections to the reference H2 embedded file database. This class
 * uses only {@code java.sql} — no {@code org.h2.*} import appears anywhere in this
 * codebase, so the runtime compiles without the H2 jar on the classpath and needs it
 * only at run time, exactly the way any JDBC application needs its driver.
 */
public final class Database {

    private final String jdbcUrl;
    private final String user;
    private final String password;

    private Database(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    /**
     * @param databaseFilePath the {@code databaseFile} path from the target contract,
     *                         e.g. {@code data/business-runtime} (H2 appends its own suffix).
     */
    public static Database embeddedFile(String databaseFilePath) {
        String url = "jdbc:h2:" + databaseFilePath + ";DB_CLOSE_DELAY=-1";
        return new Database(url, "sa", "");
    }

    public static Database inMemory(String name) {
        String url = "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
        return new Database(url, "sa", "");
    }

    public Connection getConnection() {
        try {
            return DriverManager.getConnection(jdbcUrl, user, password);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Could not open a database connection at '" + jdbcUrl + "'. "
                            + "Is the H2 driver (com.h2database:h2:2.3.232) on the runtime classpath? "
                            + e.getMessage(), e);
        }
    }
}
