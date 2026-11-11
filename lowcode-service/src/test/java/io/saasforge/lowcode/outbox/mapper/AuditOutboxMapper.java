package io.saasforge.lowcode.outbox.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 测试专用：读取写入 Outbox、等待投递的 Audit Event。Outbox 跨租户共用，不做租户改写。 */
@InterceptorIgnore(tenantLine = "true")
@Mapper
public interface AuditOutboxMapper {
    /**
     * 按写入顺序返回 Audit Event 的 JSON 文本。不受 Data Policy 过滤。
     *
     * @return 事件 JSON
     */
    List<String> selectAuditPayloads();
}
