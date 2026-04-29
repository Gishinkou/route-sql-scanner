package fixtures;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AnnotationOrderMapper {
  @Select("SELECT id, tenant_id, order_id FROM orders WHERE tenant_id = #{tenantId}")
  Object findByTenant(long tenantId);

  @Update({
      "UPDATE orders",
      "SET status = #{status}",
      "WHERE id = #{id}"
  })
  int updateWithoutRoute(long id, String status);

  @Select({
      "<script>",
      "SELECT id, tenant_id, order_id FROM orders",
      "<where>",
      "<if test='tenantId != null'>",
      "AND tenant_id = #{tenantId}",
      "</if>",
      "</where>",
      "</script>"
  })
  Object findDynamic(Long tenantId);
}
