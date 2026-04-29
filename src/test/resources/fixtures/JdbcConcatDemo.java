package fixtures;

import java.sql.Connection;

public class JdbcConcatDemo {
  public void find(Connection connection) throws Exception {
    String sql = "SELECT id, tenant_id " +
        "FROM orders " +
        "WHERE tenant_id = ?";
    connection.prepareStatement(sql);
  }
}
