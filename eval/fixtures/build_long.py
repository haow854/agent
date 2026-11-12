from __future__ import annotations

from pathlib import Path

OUT = Path(__file__).parent / "docs" / "long"
RAW_BACON = Path(__file__).parent / "raw" / "bacon_essays.txt"

SUBJECTS = ["华北大区", "华东大区", "华南大区", "西南大区", "线上商城", "线下门店",
            "会员俱乐部", "供应链中台", "客服中心", "物流网络", "内容团队", "技术平台"]
ACTIONS = ["在本周期内完成了阶段性目标的拆解与对齐", "把季度重点收敛到三件事并逐月复盘",
           "对既有流程做了一轮端到端的走查", "以双周为节奏推进试点并逐步放量",
           "先在两个城市验证再决定是否全国推开", "把跨部门依赖整理成清单并指定接口人",
           "用灰度的方式替换了原有的作业口径", "沉淀出一份可以复用的操作手册"]
OUTCOMES = ["整体进度符合年初的预期", "过程中的偏差都在阈值以内", "遗留问题已进入下个周期的排期",
            "相关数据在月度经营会上做了通晒", "复盘结论已同步给全部相关团队",
            "该项工作转入常态化运营", "经验教训写进了检查清单", "下一步将把范围扩大一倍"]

CHAPTERS_01 = [
    ("第一章 华北市场", "华北区客单价", "华北区客单价全年稳定在 217 元，与去年基本持平。"),
    ("第二章 华东市场", "华东区复购率", "华东区复购率提升明显，全年达到 31.5%，创三年新高。"),
    ("第三章 西南市场", "西南区履约时效", "西南区履约时效压缩到 18.4 小时，履约体验改善显著。"),
    ("第四章 线上支付", "线上支付成功率", "线上支付成功率保持在 99.2%，未发生重大支付故障。"),
    ("第五章 会员经营", "会员季度流失率", "会员季度流失率控制在 6.2%，续费动作前置起了作用。"),
    ("第六章 内容生态", None, None),
    ("第七章 客服体验", "客服首次响应时长", "客服首次响应时长降至 43 秒，排班算法功不可没。"),
    ("第八章 物流网络", "物流破损率", "物流破损率维持在 0.6%，包装标准化改造完成。"),
    ("第九章 仓储管理", "仓储盘点差异率", "仓储盘点差异率仅 0.35%，双人复核制度落地良好。"),
    ("第十章 技术平台", None, None),
    ("第十一章 移动端", "移动端崩溃率", "移动端崩溃率降到 0.08%，热修复通道全年可用。"),
    ("第十二章 组织建设", "年度培训覆盖率", "年度培训覆盖率达到 92%，新员工课程完成改版。"),
]

APPENDIX_01 = [
    ("华北区客单价", "217 元"),
    ("华东区复购率", "24.8%"),
    ("西南区履约时效", "26.1 小时"),
    ("线上支付成功率", "99.2%"),
    ("会员季度流失率", "9.7%"),
    ("客服首次响应时长", "71 秒"),
    ("物流破损率", "0.6%"),
    ("仓储盘点差异率", "1.12%"),
    ("移动端崩溃率", "0.23%"),
    ("年度培训覆盖率", "92%"),
]

ESSAY_COUNT_02 = 18
BODY_LIMIT_02 = 1600

TODOS_03 = [
    ("张三", "支付网关联调"), ("张三", "对账文件归档口径"), ("张三", "灰度开关默认值确认"),
    ("张三", "压测报告初稿"), ("张三", "风控接口重试参数"), ("张三", "发布检查清单更新"),
    ("张三", "数据字典补全"), ("张三", "告警阈值复核"),
    ("李四", "客服话术模板改版"), ("李四", "工单标签体系梳理"), ("李四", "退款流程时序图"),
    ("李四", "满意度问卷投放"), ("李四", "知识库过期条目清理"), ("李四", "值班排班表调整"),
    ("李四", "新人培训材料录屏"), ("李四", "会员权益页文案终稿"),
    ("王五", "机房巡检项扩充"), ("王五", "备份恢复演练脚本"), ("王五", "域名证书续期"),
    ("王五", "日志采集组件升级"), ("王五", "容量水位月报模板"), ("王五", "堡垒机权限盘点"),
    ("王五", "镜像仓库清理策略"),
]

DONE_03 = [
    ("张三", "旧网关下线公告"),
    ("李四", "一季度复盘纪要归档"),
    ("王五", "测试环境重建"),
]


def filler_sentences(seed, count):
    sentences = []
    for offset in range(count):
        index = seed * 7 + offset
        sentences.append(
            f"{SUBJECTS[index % len(SUBJECTS)]}{ACTIONS[index % len(ACTIONS)]}，"
            f"{OUTCOMES[(index // 3) % len(OUTCOMES)]}。")
    return sentences


def parse_bacon_essays():
    lines = RAW_BACON.read_text(encoding="utf-8").splitlines()
    toc_titles = [line.strip() for line in lines
                  if line.startswith("     Of ") or line.startswith("     Of the ")]
    heading_set = set(toc_titles)
    essays = []
    current = None
    for line in lines:
        if line.strip() in heading_set and not line.startswith(" "):
            if current:
                essays.append(current)
            current = {"title": line.strip(), "lines": []}
        elif current is not None:
            current["lines"].append(line)
    if current:
        essays.append(current)
    parsed = []
    for essay in essays:
        text = "\n".join(essay["lines"]).strip()
        paragraph = text.split("\n\n")[0].replace("\n", " ").strip()
        if len(paragraph) > BODY_LIMIT_02:
            cut = paragraph.rfind(". ", 0, BODY_LIMIT_02)
            paragraph = paragraph[:cut + 1] if cut > 0 else paragraph[:BODY_LIMIT_02]
        if len(paragraph) >= 200:
            parsed.append({"title": essay["title"], "body": paragraph})
    return parsed[:ESSAY_COUNT_02]


def build_long01():
    lines = ["# 年度经营报告", "",
             "本报告按业务域分十二章总结全年经营情况，文末附录汇总关键指标。"
             "正文与附录如有出入，以复核后的口径为准。", ""]
    for index, (title, _, metric_sentence) in enumerate(CHAPTERS_01):
        lines.append(f"## {title}")
        lines.append("")
        body = filler_sentences(index * 5 + 1, 12)
        paragraph_one = "".join(body[:6])
        if metric_sentence:
            paragraph_one += metric_sentence
        paragraph_one += "".join(body[6:])
        lines.append(paragraph_one)
        lines.append("")
        for offset in range(2, 6):
            lines.append("".join(filler_sentences(index * 5 + offset, 12)))
            lines.append("")
    lines.append("## 附录：关键指标汇总表")
    lines.append("")
    lines.append("| 指标 | 全年口径 |")
    lines.append("| --- | --- |")
    for name, value in APPENDIX_01:
        lines.append(f"| {name} | {value} |")
    lines.append("")
    (OUT / "long01_report.md").write_text("\n".join(lines), encoding="utf-8")


def build_long02():
    essays = parse_bacon_essays()
    if len(essays) < ESSAY_COUNT_02:
        raise SystemExit(f"bacon corpus only yielded {len(essays)} essays")
    lines = ["# Selected Essays of Francis Bacon", "",
             "An anthology of eighteen essays, reproduced from the 1625 edition. "
             "Public domain text via Project Gutenberg (ebook #575).", ""]
    for essay in essays:
        lines.append(f"## {essay['title']}")
        lines.append("")
        lines.append(essay["body"])
        lines.append("")
    (OUT / "long02_main.md").write_text("\n".join(lines), encoding="utf-8")

    backup = ["# Selected Essays of Francis Bacon (backup draft)", "",
              "This is a pre-publication backup of the anthology. "
              "It can be deleted once the final version is confirmed.", ""]
    (OUT / "long02_backup.md").write_text("\n".join(backup), encoding="utf-8")

    print("long02 sections:")
    for number, essay in enumerate(essays, start=1):
        print(f"  Section {number}. {essay['title']}")
    print("long02 sentinels:")
    for index in (1, 9, 16):
        sentence = essays[index]["body"]
        cut = sentence.find(". ")
        print(f"  [{index}] {sentence[:cut + 1] if cut > 0 else sentence[:120]}")


def build_long03():
    assignments = {index: [] for index in range(10)}
    for position, (owner, phrase) in enumerate(TODOS_03):
        assignments[position % 10].append(
            f"【待办】{owner}：负责{phrase}，请在下次周会前给出进展。")
    for position, (owner, phrase) in enumerate(DONE_03):
        assignments[(position * 4 + 1) % 10].append(
            f"【已完成】{owner}：{phrase}已经收尾，无需跟进。")
    for index in range(10):
        number = index + 1
        lines = [f"# 项目周会纪要（第 {number:02d} 期）", "",
                 f"时间：2026 年第 {number + 20} 周周四下午；"
                 "参会：张三、李四、王五及各模块同学。", "",
                 "## 讨论纪要", ""]
        for paragraph_index in range(4):
            lines.append("".join(filler_sentences(200 + index * 7 + paragraph_index, 9)))
            lines.append("")
        lines.append("## 行动项")
        lines.append("")
        for item in assignments[index]:
            lines.append(f"- {item}")
        if not assignments[index]:
            lines.append("- 本期无新增行动项。")
        lines.append("")
        (OUT / f"long03_minutes_{number:02d}.md").write_text(
            "\n".join(lines), encoding="utf-8")


CHAPTERS_04 = [
    ("华东大区退货率", "华东大区退货率全年为 2.31%，处于历史低位。", "2.13%"),
    ("华南大区客单价", "华南大区客单价全年为 226 元，同比小幅上涨。", "262 元"),
    ("西北大区履约时效", "西北大区履约时效为 21.6 小时，较年初改善明显。", "21.9 小时"),
    ("会员月活跃率", "会员月活跃率稳定在 58.4%，权益改版后止跌回升。", "58.9%"),
    ("新客首单转化率", "新客首单转化率为 12.7%，投放收紧后仍保持韧性。", "12.1%"),
    ("客服工单一次解决率", "客服工单一次解决率达到 83.6%，知识库改造见效。", "86.3%"),
    ("仓储人效", "仓储人效提升至每小时 142 件，动线改造完成。", "148 件"),
    ("干线准点率", "干线准点率保持 96.8%，雨季波动可控。", "96.2%"),
    ("售后平均处理时长", "售后平均处理时长压缩到 5.4 小时，流程并联化落地。", "4.5 小时"),
    ("移动端启动耗时", "移动端启动耗时优化到 1.8 秒，冷启动专项收官。", "1.6 秒"),
    ("支付渠道故障次数", "支付渠道全年故障 3 次，均在半小时内恢复。", "5 次"),
    ("内容审核积压量", "内容审核积压量月末均值为 420 条，人力排班已调整。", "240 条"),
    ("华北大区客单价", "华北大区客单价全年为 208 元，与去年持平。", "208 元"),
    ("线上支付成功率", "线上支付成功率保持 99.3%，未发生重大故障。", "99.3%"),
    ("年度专利申请数", "年度专利申请数为 17 件，集中在检索与压缩方向。", "17 件"),
    ("培训人均课时", "培训人均课时达到 24 课时，覆盖全部序列。", "24 课时"),
    ("官网可用性", "官网可用性全年 99.95%，达成既定目标。", "99.95%"),
    ("供应商准入通过率", "供应商准入通过率为 41%，标准未放宽。", "41%"),
]

MARKERS_05 = {
    1: "这里的口径说明暂缺，待补充编号一。",
    5: "这里的口径说明暂缺，待补充编号二。",
    10: "这里的口径说明暂缺，待补充编号三。",
}

SENTINELS_05 = {
    3: "例会纪要的归档路径全年未变。",
    8: "所有口径变更都要在数据字典留痕。",
}

DEPTS_06 = [("数据平台部", 9, 380), ("市场部", 8, 520), ("客服部", 7, 210),
            ("供应链部", 9, 460), ("财务部", 7, 150)]

SURNAMES_06 = ["赵", "钱", "孙", "李", "周", "吴", "郑", "王", "冯", "陈"]
GIVEN_06 = ["致远", "明轩", "书瑶", "沛山"]


def build_long04():
    lines = ["# 全年指标核对报告", "",
             "本报告按指标分十八章说明全年情况，文末附录汇总关键指标口径。"
             "正文与附录如有出入，说明存在待核实的矛盾。", ""]
    for index, (name, sentence, _) in enumerate(CHAPTERS_04):
        lines.append(f"## 第{index + 1}章 {name}")
        lines.append("")
        body = filler_sentences(400 + index * 5, 12)
        lines.append("".join(body[:6]) + sentence + "".join(body[6:]))
        lines.append("")
        for offset in range(1, 4):
            lines.append("".join(filler_sentences(400 + index * 5 + offset, 12)))
            lines.append("")
    lines.append("## 附录：关键指标汇总表")
    lines.append("")
    lines.append("| 指标 | 全年口径 |")
    lines.append("| --- | --- |")
    for name, sentence, appendix_value in CHAPTERS_04:
        lines.append(f"| {name} | {appendix_value} |")
    lines.append("")
    (OUT / "long04_report.md").write_text("\n".join(lines), encoding="utf-8")


def build_long05():
    lines = ["# 数据口径手册", "",
             "本手册分十二节说明各业务域的统计口径，待补充处已标注。", ""]
    for index in range(12):
        lines.append(f"## 第{index + 1}节")
        lines.append("")
        for offset in range(4):
            paragraph = "".join(filler_sentences(600 + index * 6 + offset, 13))
            if offset == 3 and index in MARKERS_05:
                paragraph += MARKERS_05[index]
            if offset == 3 and index in SENTINELS_05:
                paragraph += SENTINELS_05[index]
            lines.append(paragraph)
            lines.append("")
    (OUT / "long05_manual.md").write_text("\n".join(lines), encoding="utf-8")


def build_long06():
    people = []
    for index in range(40):
        people.append(SURNAMES_06[index % 10] + GIVEN_06[index // 10])
    dept_pool = []
    for dept, count, _ in DEPTS_06:
        dept_pool.extend([dept] * count)
    import random
    random.Random(11).shuffle(dept_pool)
    files = {index: [] for index in range(10)}
    for index, (person, dept) in enumerate(zip(people, dept_pool)):
        files[index % 10].append(f"{person}——{dept}")
    for index in range(10):
        number = index + 1
        lines = [f"# 部门花名册（分册 {number:02d}）", "",
                 "以下人员信息以人事系统为准，本分册只含部分人员。", ""]
        for row in files[index]:
            lines.append(row)
            lines.append("")
        (OUT / f"long06_roster_{number:02d}.md").write_text(
            "\n".join(lines), encoding="utf-8")
    budget = ["# 部门年度预算总表", ""]
    for dept, _, amount in DEPTS_06:
        budget.append(f"{dept}：{amount} 万元")
        budget.append("")
    (OUT / "long06_budget.md").write_text("\n".join(budget), encoding="utf-8")
    print("long06 truth:", [(dept, count, amount) for dept, count, amount in DEPTS_06])


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    build_long01()
    build_long02()
    build_long03()
    build_long04()
    build_long05()
    build_long06()
    for path in sorted(OUT.glob("*.md")):
        print(f"{path.name}: {len(path.read_text(encoding='utf-8'))} chars")


if __name__ == "__main__":
    main()
