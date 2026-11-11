# 12: API 数据集在内存中聚合

**What to build:** 绑定 API 数据集的 Widget 与 SQL 数据集一样支持聚合、日期分组、排序、Top N 与静态过滤。见规格第 4、5 节。

**Blocked by:** 07, 10

**Status:** ready-for-agent

- [ ] 在服务端内存中完成聚合、分组、排序、过滤，语义与 SQL 方言一致
- [ ] 受最大行数约束，超出时给出可读提示而非静默截断
- [ ] 与 SQL 方言共用一组结果断言用例
