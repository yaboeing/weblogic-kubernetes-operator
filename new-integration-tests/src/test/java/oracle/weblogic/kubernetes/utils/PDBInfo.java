// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes.utils;

public class PDBInfo {
  private String rcuConnectString;
  private String jdbcUrl;
  private String userName;
  //private char[] base64EncodePassword;
  private char[] password;
  private String pdbName;

  public PDBInfo(String rcuConnectString, String jdbcUrl, String pdbName, String userName,
                 char[] password) {
    this.rcuConnectString = rcuConnectString;
    this.jdbcUrl = jdbcUrl;
    this.pdbName = pdbName;
    this.userName = userName;
    this.password = password;
  }

  /**
   * @return RCU connect String that can be used to run rcu on this DB.
   */
  public String getRcuConnectString() {
    return rcuConnectString;
  }

  /**
   * Set the RCU connect String that can be used to run rcu on this DB.
   *
   * @param rcuConnectString - rcu connect string
   */
  public void setRcuConnectString(String rcuConnectString) {
    this.rcuConnectString = rcuConnectString;
  }

  /**
   * @return JDBC Url that can be used to connect to this DB.
   */
  public String getJdbcUrl() {
    return jdbcUrl;
  }

  /**
   * Set the JDBC url for this DB.
   *
   * @param jdbcUrl - jdbc url
   */
  public void setJdbcUrl(String jdbcUrl) {
    this.jdbcUrl = jdbcUrl;
  }


  /**
   * @return DB username to connect to this DB.
   */
  public String getUserName() {
    return userName;
  }

  /**
   * Set DB username to connect to this DB.
   *
   * @param userName - db username
   */
  public void setUserName(String userName) {
    this.userName = userName;
  }

  /**
   * @return name of this PDB.
   */
  public String getPdbName() {
    return pdbName;
  }

  /**
   * Sets the name of PDB.
   *
   * @param pdbName - name of pdb
   */
  public void setPdbName(String pdbName) {
    this.pdbName = pdbName;
  }

  /**
   * @return Base64 encoded password for DB.
   */
  public char[] getBase64EncodePassword() {
    return base64EncodePassword;
  }

  /**
   * Set Base64 encoded password for DB.
   *
   * @param base64EncodePassword - base64 encoded password
   */
  public void setBase64EncodePassword(char[] base64EncodePassword) {
    this.base64EncodePassword = base64EncodePassword;
  }

}

