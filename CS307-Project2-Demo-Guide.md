# CS307 Project 2 答辩展示完整指令

## 运行测试命令

```bash
# 运行所有测试
mvn test

# 单独运行LRUReplacer测试
mvn test -Dtest="storage.LRUReplacerTest" -DfailIfNoTests=false

# 单独运行ClockReplacer测试
mvn test -Dtest="storage.ClockReplacerTest" -DfailIfNoTests=false

# 单独运行事务管理器测试
mvn test -Dtest="system.TransactionManagerTest" -DfailIfNoTests=false

# 同时运行三个核心测试
mvn test -Dtest="storage.LRUReplacerTest,storage.ClockReplacerTest,system.TransactionManagerTest" -DfailIfNoTests=false

# 运行B+树索引测试
mvn test -Dtest="index.BPlusTreeTest" -DfailIfNoTests=false

# 运行高级功能测试（ALTER TABLE、INDEX、Value等）
mvn test -Dtest="feature.AdvancedFeatureTest" -DfailIfNoTests=false
```

---

## Task 0: 启动数据库

```bash
# 进入到项目目录
cd E:\CS307-proj2

# 编译项目
mvn compile -q

# 运行数据库交互界面
mvn exec:java -Dexec.mainClass="edu.sustech.cs307.DBEntry"
# 或直接运行 DBEntry.java 的 main 方法
```

---

## ⚠️ 重要：命令行使用规范

JLine的`scanner.readLine()`**每次只读取一行**。这意味着：
1. ❌ **不支持多行粘贴**：用回车换行的多行VALUES会被拆成多条独立的错误命令
2. ❌ **不支持分号分隔多条**：`insert into t values (1); insert into t values (2)` — 只有第一条被执行
3. ✅ **每条/整条SQL必须在一行内完成**

### 单行插入（推荐：每条30+行要一条条输入）
```sql
insert into student(id, name, age, gpa) values (1, 'Alice', 20, 3.8);
insert into student(id, name, age, gpa) values (2, 'Bob', 21, 3.5);
-- ... 每行一条，输入32次
```

### 单行多行VALUES（所有内容在同一行，无换行！）
把整个INSERT语句**手动合并成一行**：
```sql
insert into student(id, name, age, gpa) values (1, 'Alice', 20, 3.8), (2, 'Bob', 21, 3.5), (3, 'Charlie', 19, 3.9), (4, 'David', 22, 3.2), (5, 'Eve', 20, 3.7), (6, 'Frank', 23, 2.8), (7, 'Grace', 21, 3.6), (8, 'Helen', 19, 3.4), (9, 'Ivy', 22, 3.1), (10, 'Jack', 20, 3.3), (11, 'Kevin', 24, 2.5), (12, 'Leo', 21, 3.8), (13, 'Mia', 19, 4.0), (14, 'Nick', 23, 2.9), (15, 'Olivia', 20, 3.5), (16, 'Paul', 22, 3.0), (17, 'Quinn', 21, 3.2), (18, 'Rose', 19, 3.6), (19, 'Sam', 24, 2.7), (20, 'Tina', 20, 3.9), (21, 'Uma', 22, 3.3), (22, 'Victor', 21, 3.1), (23, 'Wendy', 19, 3.7), (24, 'Xander', 23, 2.6), (25, 'Yvonne', 20, 3.4), (26, 'Zack', 25, 2.3), (27, 'Amy', 22, 3.0), (28, 'Ben', 21, 3.8), (29, 'Cathy', 19, 3.5), (30, 'Dennis', 24, 2.4), (31, 'Ella', 20, 3.6), (32, 'Fred', 18, 3.2);
```

**INSERT执行后**，输出 `numberOfInsertRows = N` 表示实际插入的行数。

---

## Task 1: 存储管理 (20分)

### 1.1 LRU Replacer (10分)
运行测试验证：
```bash
mvn test -Dtest="storage.LRUReplacerTest" -DfailIfNoTests=false
```
预期输出：`Tests run: 13, Failures: 0`

### 1.2 Clock Replacer (10分)
运行测试验证：
```bash
mvn test -Dtest="storage.ClockReplacerTest" -DfailIfNoTests=false
```
预期输出：`Tests run: 15, Failures: 0`

---

## Task 2: 查询处理 (60分)

### 数据准备（至少30条记录）

> ⚠️ **注意**：由于JLine一次只读一行，以下多行SQL仅为文档展示格式。
> 实际使用时，有两种方式：
> - **方法A**：逐条输入 `insert into ... values (1, ...);`，每条独立一行
> - **方法B**：把整条INSERT语句手动合并成一行（见上方「单行多行VALUES」示例）

```sql
-- 创建测试表（支持INT, CHAR/VARCHAR, FLOAT/DOUBLE）
create table student(id int, name char, age int, gpa float);

-- 验证
show tables;
describe student;

-- 演示：逐条插入32条数据（每条一行，分别回车）
insert into student(id, name, age, gpa) values (1, 'Alice', 20, 3.8);
insert into student(id, name, age, gpa) values (2, 'Bob', 21, 3.5);
insert into student(id, name, age, gpa) values (3, 'Charlie', 19, 3.9);
-- ... 以此类推，逐条输入
-- 或者使用单行逗号分隔VALUES（见上方说明）
```

### 1.1 Basic DDL (20分)

```sql
-- SHOW TABLES
show tables;

-- DESCRIBE table
describe student;

-- EXPLAIN 查询计划
explain select student.id, student.name from student where student.age > 18;

-- DROP TABLE（然后重新创建）
create table test_drop(id int);
show tables;
drop table test_drop;
show tables;  -- test_drop 消失
```

### 1.2 逻辑和物理运算符 (20分)

```sql
-- 投影操作（任意列选择）
select student.id, student.name from student;

-- 等值查询
select * from student where student.id = 5;

-- 范围查询
select * from student where student.age > 21;
select * from student where student.age >= 21;
select * from student where student.age < 20;
select * from student where student.age <= 20;

-- AND 逻辑
select * from student where student.age > 20 and student.gpa > 3.5;

-- OR 逻辑
select * from student where student.age = 19 or student.gpa > 3.8;

-- 混合条件
select * from student where student.age > 20 and student.gpa > 3.0 or student.name = 'Alice';

-- 不等于
select * from student where student.age <> 20;

-- DELETE 操作（带条件）
insert into student(id, name, age, gpa) values (100, 'DeleteTest', 99, 1.0);
select * from student where student.id = 100;
delete from student where student.id = 100;
select * from student where student.id = 100;  -- 查不到

-- UPDATE 操作
select * from student where student.id = 1;
update student set student.name = 'AliceUpdated' where student.id = 1;
select * from student where student.id = 1;  -- name 被更新
```

### 1.3 SeqScan + COUNT (10分+10分)

```sql
-- 全表扫描 (SELECT *)
select * from student;

-- COUNT 操作
select count(*) from student;

-- COUNT 带条件
select count(*) from student where student.age > 20;
select count(*) from student where student.gpa >= 3.5;
```

### 2. Advanced: 高级功能 (10分)

```sql
-- MAX / MIN 聚合
select max(student.gpa) from student;
select min(student.gpa) from student;
select max(student.age) from student where student.gpa > 3.0;

-- GROUP BY
select count(*), student.age from student group by student.age;

-- ORDER BY
select * from student order by student.gpa;

-- ORDER BY 降序
select student.name, student.gpa from student order by student.gpa desc;

-- 聚合+排序
select student.age, count(*) from student group by student.age order by student.age;

-- IN / NOT IN
select * from student where student.age in (19, 20, 21);
select * from student where student.age not in (19, 20);

-- EXISTS / NOT EXISTS
select * from student s1 where exists (select * from student s2 where s2.age = s1.age and s2.gpa > 3.8);

-- ALTER TABLE ADD/DROP COLUMN（修改元数据）
create table test_alter(id int, name char);
describe test_alter;
alter table test_alter add column age int;
describe test_alter;  -- 多了 age 列
alter table test_alter drop column name;
describe test_alter;  -- name 列消失
drop table test_alter;
```

---

## Task 3: Index (10分)

### 3.1 索引测试

```sql
-- 创建索引
create index idx_age on student(age);

-- 带索引的查询（会自动使用 InMemoryIndexScanOperator）
select * from student where student.age = 20;

-- 删除索引
drop index idx_age;
```

### 3.2 B+ Tree 打印验证

运行测试验证索引功能：
```bash
mvn test -Dtest="index.BPlusTreeTest" -DfailIfNoTests=false
```

进阶测试：
```bash
mvn test -Dtest="feature.AdvancedFeatureTest" -DfailIfNoTests=false
```

### 3.3 printTree() 输出示例解释

在代码中测试 B+ 树打印：
```java
// 示例：创建degree=4的B+树，插入1-10
BPlusTreeIndex<Integer, String> tree = new BPlusTreeIndex<>(4);
for (int i = 1; i <= 10; i++) {
    tree.insert(i, "v" + i);
}
tree.printTree();
```

预期输出：
```
========== B+ Tree Structure ==========
Degree: 4, MinKeys: 1, Total Nodes: 4
[ROOT] keys=2/3  keys=[3, 7]
├── [LEAF] keys=2/3  keys=[1, 2]  values=[v1, v2]  next→[3, 5]
├── [LEAF] keys=2/3  keys=[3, 5]  values=[v3, v5]  next→[7, 10]
└── [LEAF] keys=2/3  keys=[7, 10]  values=[v7, v10]  next→null
========================================
```

### 3.4 现场修改degree演示

```java
// degree=4 → 每个节点最多3个key
BPlusTreeIndex<Integer, String> tree1 = new BPlusTreeIndex<>(4);
for (int i = 1; i <= 8; i++) tree1.insert(i, "v" + i);
tree1.printTree();
// 高度为2: 1个ROOT + 3个LEAF

// degree=3 → 每个节点最多2个key（节点分裂更频繁）
BPlusTreeIndex<Integer, String> tree2 = new BPlusTreeIndex<>(3);
for (int i = 1; i <= 8; i++) tree2.insert(i, "v" + i);
tree2.printTree();
// 高度为3或更多: 因为每个节点只能放2个key

// degree=6 → 每个节点最多5个key（节点很少分裂）
BPlusTreeIndex<Integer, String> tree3 = new BPlusTreeIndex<>(6);
for (int i = 1; i <= 8; i++) tree3.insert(i, "v" + i);
tree3.printTree();
// 可能只有1个LEAF节点，无内部节点
```

---

## Task 4: Transaction (8分)

运行测试验证：
```bash
mvn test -Dtest="system.TransactionManagerTest" -DfailIfNoTests=false
```
预期输出：`Tests run: 10, Failures: 0`

进入交互界面后的演示步骤：

```sql
-- 准备数据
create table txn_test(id int);

-- 场景1: BEGIN + INSERT + ROLLBACK → 数据撤销
begin;
insert into txn_test(id) values (1);
insert into txn_test(id) values (2);
select * from txn_test;  -- 有1,2
rollback;
select * from txn_test;  -- 空（已回滚）

-- 场景2: BEGIN + INSERT + COMMIT → 数据持久化
begin;
insert into txn_test(id) values (10);
insert into txn_test(id) values (20);
commit;
select * from txn_test;  -- 有10,20

-- 场景3: SAVEPOINT + ROLLBACK TO
begin;
insert into txn_test(id) values (100);
savepoint after_insert;
insert into txn_test(id) values (200);
select * from txn_test;  -- 有100,200
rollback to savepoint after_insert;
select * from txn_test;  -- 有100（200已被回滚）
commit;

-- 场景4: RELEASE SAVEPOINT
begin;
insert into txn_test(id) values (1);
savepoint sp1;
insert into txn_test(id) values (2);
release savepoint sp1;
-- 不能再回滚到sp1
rollback to savepoint sp1;  -- 应报错
commit;
```

---

## Task 5: Presentation (10分)

### 命令行界面展示
```sql
help;    -- 显示帮助信息
exit;    -- 退出程序
```

### 异常处理展示
```sql
-- 表不存在
select * from nonexistent_table;

-- 列不存在
select student.nonexistent_col from student;

-- 重复创建表
create table student(id int);  -- 应报错 TableAlreadyExist

-- INSERT 类型不匹配
insert into student(id, name, age, gpa) values ('abc', 'test', 20, 3.0);  -- 类型不匹配

-- 事务异常
savepoint outside_tx;  -- 没有事务时报错
```

---

## 全部测试一键运行

```bash
# 运行所有测试（包含本项目所有JUnit测试）
mvn test

# 仅运行核心的三个必须通过的测试
mvn test -Dtest="storage.LRUReplacerTest,storage.ClockReplacerTest,system.TransactionManagerTest" -DfailIfNoTests=false
```

---

## 答辩问答准备清单

| 问题 | 关键回答要点 |
|------|-------------|
| LRU如何工作？ | LinkedList实现，Victim()从尾部移除，Unpin()加到头部 |
| Clock算法原理？ | 环形扫描，refBit=1给第二次机会，refBit=0驱逐 |
| SeqScan如何实现？ | 遍历page→遍历slot→检查bitmap→读取record |
| COUNT怎么实现？ | FilterOperator过滤 → ProjectOperator收集 → evalAggregate |
| 事务Snapshot怎么做的？ | persistRuntimeState() → 复制整个DB目录到临时文件夹 |
| COMMIT发生了什么？ | persistRuntimeState()写入磁盘 → 删除snapshot目录 |
| SAVEPOINT设计？ | 每创建一个savepoint就复制一个完整snapshot |
| B+树分裂条件？ | keys.size() > degree - 1 |
| B+树degree修改影响？ | degree越大节点越大，树越矮；degree越小节点越小，树越高 |
| 持久化怎么保证？ | FlushPage()用channel.force(true)强制刷盘 |