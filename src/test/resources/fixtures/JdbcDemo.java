package fixtures;

import java.sql.Connection;

public class JdbcDemo {
  public void find(Connection connection, long id) throws Exception {
    connection.prepareStatement("SELECT id, status FROM orders WHERE id = ?");
  }
}
