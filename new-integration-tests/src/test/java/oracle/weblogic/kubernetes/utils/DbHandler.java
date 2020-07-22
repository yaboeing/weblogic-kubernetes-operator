// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;

import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.logging.Logger;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;

import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.awaitility.Awaitility.with;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class DbHandler {
  public static final String JDBC_URL_PREFIX = "jdbc:oracle:thin:@//";
  public static final String CDB_DEFAULT_URL =
      "sfelts-1.subnet1ad2phx.devweblogicphx.oraclevcn.com:5521/rdbms.regress.rdbms.dev.us.oracle.com";
  public static final String CDB_DEFAULT_JDBC_URL = JDBC_URL_PREFIX + CDB_DEFAULT_URL;
  public static final String CDB_DEFAULT_USERNAME = "sys as sysdba";
  private static final char[] CDB_DEFAULT_PASSWORD_BASE64_ENCODED = encodePassword('w', 'e', 'l', 'c', 'o', 'm', 'e',
      '1');

  public static final String CDB_DEFAULT_JDBC_DRIVER_CLASS_NAME = "oracle.jdbc.driver.OracleDriver";

  private static final String VERIFY_CDB_SQL = "select cdb from v$database";
  private static final String DATABASE_IS_CDB = "YES";
  private static final String PDB_NAME_TOKEN = "$pdbName$";
  private static final String CREATE_PDB_SQL = join("create pluggable database ", PDB_NAME_TOKEN,
      " admin user admin identified by admin file_name_convert = ('pdbseed', '", PDB_NAME_TOKEN, "')");

  private static final String OPEN_PDB_SQL = join("alter pluggable database ", PDB_NAME_TOKEN, " open");
  private static final String PDB_STATUS_SQL = "select OPEN_MODE from v$pdbs WHERE NAME = upper(?)";
  private static final String CLOSE_PDB_SQL = join("alter pluggable database ", PDB_NAME_TOKEN, " close");
  private static final String DROP_PDB_SQL = join("drop pluggable database ", PDB_NAME_TOKEN, " INCLUDING DATAFILES");
  private static final String SELECT_OLD_PDBS_SQL = "select name, open_mode from v$pdbs where open_time < (sysdate - interval '2' hour) and length(name) = 30";

  private static final String PDB_STATUS_MOUNTED = "MOUNTED";
  private static final String PDB_STATUS_READ_WRITE = "READ WRITE";
  private static final String PDB_STATUS_NONE = "NONE";

  private static final String PDB_SERVICE_NAME_SUFFIX = "";

  //public static final String RCU_CONNECT_STRING_KEY = "rcuConnectString";
  public static final String JDBC_URL_KEY = "jdbcUrl";

  //private static String rcuConnectString;
  private static String jdbcUrl = CDB_DEFAULT_JDBC_URL;
  private static String username = CDB_DEFAULT_USERNAME;
  private static char[] password = CDB_DEFAULT_PASSWORD_BASE64_ENCODED;
  private static String dbDriver = CDB_DEFAULT_JDBC_DRIVER_CLASS_NAME;
  //private Logger logger = Logger.getLogger(this.getClass().getName());

  //TODO remove
  /*public DbHandler() {
    jdbcUrl = CDB_DEFAULT_JDBC_URL;
    username = CDB_DEFAULT_USERNAME;
    password = CDB_DEFAULT_PASSWORD_BASE64_ENCODED;
    dbDriver = CDB_DEFAULT_JDBC_DRIVER_CLASS_NAME;
  }*/

  public static String getJdbcUrl() {
    return jdbcUrl;
  }

  public void setJdbcUrl(String jdbcUrl) {
    this.jdbcUrl = jdbcUrl;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public char[] getPassword() {
    return password;
  }

  public void setPassword(char[] password) {
    this.password = password;
  }

  public String getDbDriver() {
    return dbDriver;
  }

  public void setDbDriver(String dbDriver) {
    this.dbDriver = dbDriver;
  }

  public static char[] getDefaultPassword() {
    return CDB_DEFAULT_PASSWORD_BASE64_ENCODED;
  }

  /**
   * Creates a PDB.
   * <ol>
   * <li>Verifies that the JDBC url points to a valid CDB$ROOT</li>
   * <li>Verifies that a PDB with given name does not already exists in database</li>
   * <li>if validations in step 1 and 2 are successful - creates a PDB using
   * {@link #performCreatePDB(Connection, String)}</li>
   * <li>Verifies that pdb is in <code>MOUNTED</code> state after creation and if that is s true</li>
   * <li>opens the pdb and verifies that PDB is in <code>READ WRITE</code> state</li>
   * <li>If PDB has been successfully opened in 5 - verifies the jdbc connection to PDB</li>
   * <ol>
   *
   * @param pdbName          - Name of the pdb to be created.
   * @return - true if pdb has been successfully created and verified.
   */
  public static boolean createPDB(String pdbName) {
    LoggingFacade logger = getLogger();
    boolean status = false;
    if (loadDriver()) {
      try (Connection con = DriverManager.getConnection(jdbcUrl, username,
          new String(decodePassword(password)));) {
        logger.info(" Checking if database with jdbcUrl: {0}, userName: {1}", jdbcUrl, username,
            " is a CDB.");
        if (isCDB(con)) {
          if (verifyPDBNotExists(con, pdbName)) {
            logger.info(" Creating PDB ", pdbName);
            performCreatePDB(con, pdbName);
            if (verifyPDBMountedState(con, pdbName)) {
              logger.info("PDB: {0} mounted ", pdbName);
              performOpenPDB(con, pdbName);
              if (verifyPDBOpenState(con, pdbName)) {
                logger.info("PDB: {0} opened", pdbName);
                String jdbcUrlForPDB = getJdbcUrlForPDB(pdbName);
                status = testJdbcConnection( jdbcUrlForPDB, username, password);
                logger.info( " PDB ", pdbName,
                    status ? " successfully pinged" : " could not be connected", " at JDBC URL ",
                    jdbcUrlForPDB);
              } else {
                logger.info("PDB: {0} could not be opened", pdbName);
              }
            } else {
              logger.info("PDB: {0} could not be created and mounted at CDB pointed to by jdbcUrl: {1}",
                  pdbName, jdbcUrl);
            }
          } else {
            logger.info("PDB: {0} already exists in CDB ", pdbName);
          }
        } else {
          logger.info(" jdbUrl: {0 }does not point to a valid CDB", jdbcUrl);
        }
      } catch (SQLException e) {
        logger.info( "Exception occured while creating PDB: {0} in database with jdbcUrl: {1}, " +
            "with user: {2} ", pdbName, jdbcUrl, username);
        logger.info( ExceptionUtils.getFullStackTrace(e));
      }
    }
    return status;
  }

  private static boolean isCDB(Connection con) throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(VERIFY_CDB_SQL); ResultSet rs = ps.executeQuery()) {
      rs.next();
      return DATABASE_IS_CDB.equals(rs.getString(1));
    }
  }

  private static void performCreatePDB(Connection con, String pdbName) throws SQLException {
    executePDBQuery(con, pdbName, CREATE_PDB_SQL);
  }

  private static boolean verifyPDBMountedState(Connection con, String pdbName) throws SQLException {
    return PDB_STATUS_MOUNTED.equals(getPDBStatus(con, pdbName));
  }

  private static void performOpenPDB(Connection con, String pdbName) throws SQLException {
    executePDBQuery(con, pdbName, OPEN_PDB_SQL);
  }

  private static boolean verifyPDBOpenState(Connection con, String pdbName) throws SQLException {
    return PDB_STATUS_READ_WRITE.equals(getPDBStatus(con, pdbName));
  }

  /**
   * Drops a PDB.
   * <ol>
   * <li>Verifies that the JDBC url points to a valid CDB$ROOT</li>
   * <li>Verifies that a PDB with given name does exists in database and is not already in closed (<code>MOUNTED</code>) state.</li>
   * <li>if validations in step 1 and 2 are successful - closes a PDB using {@link #performClosePDB(Connection, String)}</li>
   * <li>Verifies that pdb is in <code>MOUNTED</code> state after closing and if thats true</li>
   * <li>Drops the pdb using {@link #performDropPDB(Connection, String)} and verifies that PDB no more exists.</li>
   * <ol>
   *
   * @param pdbName          - Name of the pdb to be dropped.
   * @return - true if pdb has been successfully dropped and verified.
   */
  public static boolean dropPDB(String pdbName) {
    LoggingFacade logger = getLogger();
    boolean status = false;
    if (loadDriver()) {
      try (Connection con = DriverManager.getConnection(jdbcUrl, username,
          new String(decodePassword(password)));) {
        logger.info(" Checking if database with JDBC URL ", jdbcUrl, " and user ", username,
            " is a CDB.");
        if (isCDB(con)) {
          if (verifyPDBExistsAndNotClosed(con, pdbName)) {
            logger.info(" Closing PDB ", pdbName);
            performClosePDB(con, pdbName);
            if (verifyPDBClosedState(con, pdbName)) {
              logger.info(" PDB ", pdbName, " closed.");
              performDropPDB(con, pdbName);
              status = verifyPDBNotExists(con, pdbName);
              logger.info("PDB: {0} ", pdbName,
                  status ? " dropped." : " could not be dropped.");
            } else {
              logger.info("PDB: {0} could not be closed at CDB pointed to by jdbcUrl: {1}",
                  pdbName, jdbcUrl);
            }
          } else {
            logger.info("PDB: {0} does not exist or is already closed. ", pdbName);
          }
        } else {
          logger.info("jdbcUrl {0} does not point to a valid CDB.", jdbcUrl);
        }
      } catch (SQLException e) {
        logger.info(" Exception occurred while removing PDB: {0} in database with jdbcUrl: {1} " +
            "with username: {2}", pdbName, jdbcUrl,  username);
        logger.info(ExceptionUtils.getFullStackTrace(e));
      }
    }
    return status;
  }

  private static boolean verifyPDBExistsAndNotClosed(Connection con, String pdbName) throws SQLException {
    return verifyPDBOpenState(con, pdbName);
  }

  private static void performClosePDB(Connection con, String pdbName) throws SQLException {
    executePDBQuery(con, pdbName, CLOSE_PDB_SQL);
  }

  private static boolean verifyPDBClosedState(Connection con, String pdbName) throws SQLException {
    return verifyPDBMountedState(con, pdbName);
  }

  private static void performDropPDB(Connection con, String pdbName) throws SQLException {
    executePDBQuery(con, pdbName, DROP_PDB_SQL);
  }

  private static boolean verifyPDBNotExists(Connection con, String pdbName) throws SQLException {
    return PDB_STATUS_NONE.equals(getPDBStatus(con, pdbName));
  }

  private static void executePDBQuery(Connection con, String pdbName, String pdbSql) throws SQLException {
    try (Statement stmt = con.createStatement();) {
      stmt.execute(StringUtils.replace(pdbSql, PDB_NAME_TOKEN, pdbName));
    }
  }

  private static String getPDBStatus(Connection con, String pdbName) throws SQLException {
    String pdbStatus = PDB_STATUS_NONE;
    try (PreparedStatement ps = con.prepareStatement(PDB_STATUS_SQL);) {
      ps.setString(1, pdbName);
      try (ResultSet rs = ps.executeQuery();) {
        if (rs.next()) {
          pdbStatus = rs.getString(1);
        }
      }
    }
    return pdbStatus;
  }

  private static boolean loadDriver() {
    LoggingFacade logger = getLogger();
    try {
      Class.forName(dbDriver);
    } catch (ClassNotFoundException clex) {
      logger.info("Unable to load: {0}, Exception: {1}", dbDriver, clex);
      logger.info(ExceptionUtils.getFullStackTrace(clex));
      return false;
    }
    return true;

  }

  //TODO
  /*private void logger.info(Object... msgs) {
    logMessage(logger, join(msgs));
  }*/

  private static String join(Object... msgs) {
    return StringUtils.join(msgs);
  }

  /**
   * Given the name of a PDB, creates a JDBC url for that PDB corresponding to JDBC url
   * populated in this instance of databse handler.
   *
   * @param pdbName - Name of pdb
   * @return - jdbc url of pdb.
   */
  public static String getJdbcUrlForPDB(String pdbName) {
    LoggingFacade logger = getLogger();
    String jdbcUrl = null;
    String[] jdbcUrlParts = jdbcUrl.split(":");
    if (jdbcUrlParts != null && jdbcUrlParts.length >= 3) {
      String[] resultArrayParts = (String[]) ArrayUtils.subarray(jdbcUrlParts, 0, jdbcUrlParts.length - 1);
      String lastPart = jdbcUrlParts[jdbcUrlParts.length - 1];
      if (lastPart.indexOf('/') >= 0) {
        lastPart = lastPart.substring(0, lastPart.indexOf('/'));
      }
      jdbcUrl = String.join("/", String.join(":", (String[]) ArrayUtils.add(resultArrayParts, lastPart)),
          join(pdbName, PDB_SERVICE_NAME_SUFFIX));
      logger.info("For pdbName {0} the jdbcUrl is: {1} ", pdbName, jdbcUrl);
      return jdbcUrl;
    }
    jdbcUrl = StringUtils.EMPTY;
    logger.info("For pdbName {0} the jdbcUrl is not set: {1} ", pdbName, jdbcUrl);
    return jdbcUrl;
  }

  /**
   * Tests a JDBC connection to database specified using input jdbc url, username and password by
   * issuing <code>select 1 from dual</code>.
   *
   * @param jdbcUrl jdbc url
   * @param userName db user name
   * @param base64EncodedPassword db password base64 encoded
   * @return - true if db ping is successful.
   */
  public static boolean testJdbcConnection(String jdbcUrl, String userName, char[] base64EncodedPassword) {
    LoggingFacade logger = getLogger();
    if (loadDriver()) {
      try (Connection con = DriverManager.getConnection(jdbcUrl, userName,
          new String(decodePassword(base64EncodedPassword)));
           Statement stmt = con.createStatement();
           ResultSet rs = stmt.executeQuery("select 1 from dual");) {
        return rs.next();
      } catch (SQLException e) {
        logger.info("Exception occurred while testing jdbcUrl: {0}, with username: {1} ",
            jdbcUrl, userName);
        logger.info(ExceptionUtils.getFullStackTrace(e));
      }
    }
    return false;

  }

  /**
   * Encodes the input password to Base64.
   *
   * @param passwordInChars - password to encode.
   * @return - encoded password.
   */
  public static char[] encodePassword(char... passwordInChars) {
    CharBuffer passwordBuffer = Charset.defaultCharset().decode(Base64.getEncoder().encode(Charset.defaultCharset().encode(CharBuffer.wrap(passwordInChars))));
    char[] encodedPassword = new char[passwordBuffer.length()];
    passwordBuffer.get(encodedPassword);
    return encodedPassword;
  }

  /**
   * Decodes input Base64 encoded password.
   *
   * @param encodedPasswordInChars - input password
   * @return - decoded password.
   */
  public static char[] decodePassword(char... encodedPasswordInChars) {
    CharBuffer passwordBuffer = Charset.defaultCharset().decode(Base64.getDecoder().decode(Charset.defaultCharset().encode(CharBuffer.wrap(encodedPasswordInChars))));
    char[] decodedPassword = new char[passwordBuffer.length()];
    passwordBuffer.get(decodedPassword);
    return decodedPassword;
  }

  /**
   * Retrieves the PDB information about pdb specified by name <code>pdbName</code> in the CDB pointed to
   * by jdbcUrl of this database handler instance and populates it in a {@link PDBInfo} object.
   * In case pdb does not exist in cdb and <code>createNewPDB</code> parameter is set to true - it is created.
   *
   * @return {@link PDBInfo} instance containing the PDB details.
   */
  /*public PDBInfo getPDBInfo(String pdbName, boolean createNewPDB, String logMessagePrefix) {
    PDBInfo pdbInfo = new PDBInfo(rcuConnectString, jdbcUrl, pdbName, getUsername(), getPassword());
    LoggingFacade logger = getLogger();
    if (createNewPDB && !createPDB(pdbName, logMessagePrefix)) {
      logger.info(" PDB {0} could not be created", pdbName);
      return null;
    }
    String jdbcUrlForPDB = getJdbcUrlForPDB(pdbName);
    if (StringUtils.isNotEmpty(jdbcUrlForPDB)) {
      pdbInfo.setPdbName(pdbName);
      pdbInfo.setJdbcUrl(jdbcUrlForPDB);
      String token = "@";
      String url = jdbcUrlForPDB.replace("//", "");
      int startIndex = url.indexOf(token);
      if (startIndex != -1) {
        String rcuConnectString = url.substring(startIndex + 1);
        pdbInfo.setRcuConnectString(rcuConnectString);
      }
      pdbInfo.setUserName(getUsername());
      pdbInfo.setBase64EncodePassword(getPassword());
    }
    return pdbInfo;
  }*/

  public static String getRcuConnectString(String pdbName) {
    LoggingFacade logger = getLogger();
    String jdbcUrlForPDB = getJdbcUrlForPDB(pdbName);
    String rcuConnectString = null;
    if (StringUtils.isNotEmpty(jdbcUrlForPDB)) {
      String token = "@";
      String url = jdbcUrlForPDB.replace("//", "");
      int startIndex = url.indexOf(token);
      if (startIndex != -1) {
        rcuConnectString = url.substring(startIndex + 1);
      }
    }
    logger.info("For pdbName: {1} rcuConnectionString is: {1}", pdbName, rcuConnectString);
    return rcuConnectString;
  }

  /**
   * Closes and drops pdbs which were opened more than 2 hours before from database.
   */
  public static void cleanUpPdbs() {
    LoggingFacade logger = getLogger();
    if (loadDriver()) {
      try (Connection con = DriverManager.getConnection(jdbcUrl, username,
          new String(decodePassword(password)));
           Statement stmt = con.createStatement();
           ResultSet rs = stmt.executeQuery(SELECT_OLD_PDBS_SQL);) {
        while (rs.next()) {
          String pdbName = rs.getString("name");
          String pdbState = rs.getString("open_mode");
          if (PDB_STATUS_READ_WRITE.equalsIgnoreCase(pdbState)) {
            logger.info("Closing pdb ", pdbName);
            performClosePDB(con, pdbName);
            logger.info("Closed pdb ", pdbName);
          }
          logger.info("Dropping pdb ", pdbName);
          performDropPDB(con, pdbName);
          logger.info("Dropped pdb ", pdbName);
        }
      } catch (SQLException e) {
        logger.info("Exception occurred while cleaning up pdbs at jdbcUtl: {0} with username: {1} ",
            jdbcUrl, username);
        logger.info(ExceptionUtils.getFullStackTrace(e));
      }
    }
  }

  /**
   * Invokes {@link #cleanUpPdbs(String)} to clean up pdbs.
   *
   * @param args - main arguments, not used
   */
  public static void main(String[] args) {
    DbHandler databaseHandler = new DbHandler();
    databaseHandler.cleanUpPdbs("pdbcleanup: ");
  }


}
