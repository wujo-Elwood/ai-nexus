# -*- coding: utf-8 -*-
"""生成其余 12 个菜单页的 HUD 样机"""
import io, os
os.chdir(os.path.dirname(os.path.abspath(__file__)))

NAV = [("kb","知识库","▣"),("chat","AI 聊天","◇"),("image","AI 生图","◈"),("canvas","生图画布","▦"),
("extract","文档抽取","≋"),("analysis","数据分析","∑"),("agents","智能体","✦"),("skills","技能管理","❖"),
("settings","模型设置","⚙"),("stats","用量统计","▥"),("health","系统健康","◉"),("tasks","任务中心","▤"),
("knowledgegap","知识缺口","⌁"),("eval","RAG 评测","✓"),("rbac","权限管理","▧")]

def nav(active):
    out = ['<div class="brand">AI Nexus</div>', '<div class="brand-sub">DEEP·SPACE·CONSOLE</div>']
    for gname, items in [("功能", NAV[:12]), ("管理", NAV[12:])]:
        out.append(f'<div class="group">// {gname}</div>')
        for key, label, sym in items:
            cls = ' class="active"' if key == active else ''
            sym_html = f'<span style="margin-right:2px;">{sym}</span>'
            out.append(f'<a{cls} href="#">{sym_html}{label}</a>')
    return "\n".join(out)

def page(fname, key, syspath, title, desc, btn, readouts, body):
    head_btn = f'<button class="btn-primary">{btn}</button>' if btn else ''
    ro = ''
    if readouts:
        items = "".join(
            f'<div class="readout"><div class="val">{v}</div><div class="lab">{l}</div>' + (f'<div class="trend">{t}</div>' if t else '') + '</div>'
            for v, l, t in readouts)
        ro = f'<div class="readouts">{items}</div>'
    html = f'''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<title>AI Nexus · {title}（深空 HUD）</title>
<link rel="stylesheet" href="hud.css">
</head>
<body>
<div class="shell">
  <aside class="side">
    {nav(active=key)}
  </aside>
  <main class="main">
    <div class="page">
      <div class="console-head">
        <div class="console-top">
          <span class="path">SYS://{syspath}</span>
          <span class="sys-label"><span class="pulse"></span>ONLINE</span>
        </div>
        <div class="head-row">
          <div>
            <h1>{title}</h1>
            <div class="desc">{desc}</div>
          </div>
          {head_btn}
        </div>
      </div>
      {ro}
      {body}
    </div>
  </main>
</div>
</body>
</html>'''
    io.open(fname, 'w', encoding='utf-8').write(html)
    print('written', fname)

LBL = '<div class="sys-label" style="margin-bottom:10px;"><span class="pulse"></span>'

# ---------- 文件管理 ----------
files_body = '''
      <div class="toolbar">
        <input class="search" placeholder="搜索文件名…">
        <div class="spacer"></div>
        <div class="filters"><a class="on" href="#">全部</a><a href="#">已就绪</a><a href="#">解析中</a><a href="#">失败</a></div>
        <button class="btn-primary">＋ 上传文件</button>
      </div>
      ''' + LBL + 'FILES // 5 ENTRIES</div>' + '''
      <div class="hud-list">
        <div class="hud-row">
          <span class="idx"><b>01</b></span>
          <div class="row-main"><div class="row-name">变压器巡检记录-2026Q3.pdf</div>
          <div class="row-desc">PDF · 12.4 MB · 184 切片 · 含 3 页扫描件</div></div>
          <span class="st ok"><span class="dot ok"></span>可问答</span>
          <div class="row-meta">更新 3 分钟前</div><button class="row-ops">⋯</button>
        </div>
        <div class="hud-row">
          <span class="idx"><b>02</b></span>
          <div class="row-main"><div class="row-name">安全规程汇编-修订版.docx</div>
          <div class="row-desc">DOCX · 2.8 MB · 96 切片 · 内嵌 4 张图</div></div>
          <span class="st ok"><span class="dot ok"></span>可问答</span>
          <div class="row-meta">更新 昨天 16:12</div><button class="row-ops">⋯</button>
        </div>
        <div class="hud-row">
          <span class="idx"><b>03</b></span>
          <div class="row-main"><div class="row-name">一次侧接线图-扫描件.pdf</div>
          <div class="row-desc">PDF · 8.1 MB · 无文字层，走视觉识别</div></div>
          <span class="st warn"><span class="dot warn"></span>视觉识别中</span>
          <div class="row-meta">第 2 / 5 页</div><button class="row-ops">⋯</button>
        </div>
        <div class="hud-row">
          <span class="idx"><b>04</b></span>
          <div class="row-main"><div class="row-name">油温记录表-10月.xlsx</div>
          <div class="row-desc">XLSX · 340 KB · 重试 1/3 次</div></div>
          <span class="st bad"><span class="dot bad"></span>解析失败</span>
          <div class="row-meta">10/2 22:14</div>
          <a class="row-cta" href="#" style="color:var(--text-2);border-color:var(--line)">重试</a>
        </div>
        <div class="hud-row">
          <span class="idx"><b>05</b></span>
          <div class="row-main"><div class="row-name">历史铭牌照片.jpg</div>
          <div class="row-desc">JPG · 1.2 MB · 视觉解析提取铭牌文字</div></div>
          <span class="st ok"><span class="dot ok"></span>可问答</span>
          <div class="row-meta">10/1 09:30</div><button class="row-ops">⋯</button>
        </div>
      </div>'''
page('hud-files.html', 'files', 'KB-01//FILES · ARCHIVE:设备运维手册', '文件管理', '设备运维手册 · 24 个文件参与检索', '＋ 上传文件',
     [("24","文件",""),("3,184","切片",""),("2.1 GB","存储",""),("1","解析中","")], files_body)

# ---------- 知识库洞察 ----------
insights_body = '''
      <div class="readouts">
        <div class="readout"><div class="val">92</div><div class="lab">健康评分</div></div>
        <div class="readout"><div class="val">24</div><div class="lab">文件</div></div>
        <div class="readout"><div class="val">3,184</div><div class="lab">切片</div><div class="trend">+184</div></div>
        <div class="readout"><div class="val">0.8%</div><div class="lab">空切片</div></div>
      </div>
      <div class="toolbar"><h1 style="font-size:16px;font-weight:600;">检索策略</h1><div class="spacer"></div></div>
      <div style="max-width:520px;">
        <div style="margin-bottom:18px;"><div style="font-size:12.5px;color:var(--text-2);margin-bottom:6px;">切片大小 / 重叠</div>
          <input style="width:200px;padding:8px 12px;border:1px solid var(--divider);border-radius:4px;background:none;color:var(--text-1);font-family:var(--mono);" value="500 / 100"></div>
        <div style="margin-bottom:18px;"><div style="font-size:12.5px;color:var(--text-2);margin-bottom:6px;">召回 Top-K / 相似度阈值</div>
          <input style="width:200px;padding:8px 12px;border:1px solid var(--divider);border-radius:4px;background:none;color:var(--text-1);font-family:var(--mono);" value="8 / 0.42"></div>
        <div style="margin-bottom:24px;"><div style="font-size:12.5px;color:var(--text-2);margin-bottom:6px;">视觉解析（扫描件与图片走视觉模型）</div>
          <span class="st ok" style="font-size:13px;"><span class="dot ok"></span>已开启 · 上限 50 页</span></div>
        <button class="btn-primary">保存策略</button>
      </div>'''
page('hud-insights.html', 'insights', 'KB-01//INSIGHTS', '策略与洞察', '设备运维手册 · 健康评分 92 · 检索与切片策略', None,
     None, insights_body)

# ---------- 文档抽取 ----------
extract_body = '''
      <div style="display:flex;gap:0;margin-bottom:26px;font-family:var(--mono);font-size:12px;">
        <span style="color:var(--text-3);padding:6px 18px;border-bottom:1px solid var(--divider);">1 模板 ✓</span>
        <span style="color:var(--text-3);padding:6px 18px;border-bottom:1px solid var(--divider);">2 上传 ✓</span>
        <span style="color:var(--primary-hi);padding:6px 18px;border-bottom:2px solid var(--primary);">3 校正</span>
        <span style="color:var(--text-3);padding:6px 18px;border-bottom:1px solid var(--divider);">4 导出</span>
      </div>
      <div class="toolbar"><h1 style="font-size:16px;font-weight:600;">校正 · 采购合同批次</h1><div class="spacer"></div>
        <button class="btn-primary">导出 Excel</button></div>
      <div class="hud-list">
        <div class="hud-row">
          <span class="idx"><b>01</b></span>
          <div class="row-main"><div class="row-name">采购合同-HT-2601.pdf</div>
          <div class="row-desc">12 个字段已抽取 · 平均置信度 96%</div></div>
          <span class="st ok"><span class="dot ok"></span>待人工确认</span>
          <a class="row-cta" href="#">进入校正 →</a>
        </div>
        <div class="hud-row">
          <span class="idx"><b>02</b></span>
          <div class="row-main"><div class="row-name">采购合同-HT-2602.pdf</div>
          <div class="row-desc">12 个字段已抽取 · 金额字段置信度偏低 71%</div></div>
          <span class="st warn"><span class="dot warn"></span>需复核</span>
          <a class="row-cta" href="#">进入校正 →</a>
        </div>
        <div class="hud-row">
          <span class="idx"><b>03</b></span>
          <div class="row-main"><div class="row-name">采购合同-HT-2603.pdf</div>
          <div class="row-desc">12 个字段已抽取 · 平均置信度 94%</div></div>
          <span class="st ok"><span class="dot ok"></span>待人工确认</span>
          <a class="row-cta" href="#">进入校正 →</a>
        </div>
      </div>'''
page('hud-extract.html', 'extract', 'EXTRACT//BATCH-2601', '文档抽取', '上传合同与单据，按模板抽取字段，人工校正后导出', None,
     [("12","文档",""),("86","字段",""),("96%","平均置信度","")], extract_body)

# ---------- 数据分析 ----------
analysis_body = '''
      <div class="toolbar"><input class="search" placeholder="搜索已上传的数据文件…"><div class="spacer"></div>
        <button class="btn-primary">＋ 上传 Excel</button></div>
      <div class="hud-list">
        <div class="hud-row">
          <span class="idx"><b>01</b></span>
          <div class="row-main"><div class="row-name">销售明细-2026Q3.xlsx</div>
          <div class="row-desc">8,412 行 · 已完成自然语言问答与分组汇总</div></div>
          <div class="row-meta">问过 6 个问题</div><a class="row-cta" href="#">查看分析 →</a>
        </div>
        <div class="hud-row">
          <span class="idx"><b>02</b></span>
          <div class="row-main"><div class="row-name">库存快照-10月.xlsx</div>
          <div class="row-desc">1,204 行 · 待分析</div></div>
          <div class="row-meta">10/2 18:40 上传</div><a class="row-cta" href="#">查看分析 →</a>
        </div>
        <div class="hud-row">
          <span class="idx"><b>03</b></span>
          <div class="row-main"><div class="row-name">客户回款表-Q3.xlsx</div>
          <div class="row-desc">342 行 · 分析完成 · 已生成 2 张图表</div></div>
          <div class="row-meta">10/1 11:05</div><a class="row-cta" href="#">查看分析 →</a>
        </div>
      </div>'''
page('hud-analysis.html', 'analysis', 'ANALYSIS//SHEETS', '数据分析', '上传 Excel 表格，用自然语言完成查询、统计与分组汇总', '＋ 上传 Excel',
     [("3","数据文件",""),("9,958","总行数",""),("9","提问次数","")], analysis_body)

# ---------- AI 生图 ----------
image_body = '''
      <div class="toolbar"><input class="search" placeholder="描述画面：主体、风格、构图、色调…" style="max-width:640px;">
        <div class="spacer"></div><button class="btn-primary">生成</button></div>
      ''' + LBL + 'HISTORY // 最近生成</div>' + '''
      <div class="hud-list">
        <div class="hud-row">
          <span class="idx"><b>01</b></span>
          <div class="row-main"><div class="row-name">深空中的空间站，电影感侧光</div>
          <div class="row-desc">gpt-image-2 · 1024×1024 · 生成耗时 18s</div></div>
          <span class="st ok"><span class="dot ok"></span>已完成</span>
          <div class="row-meta">10/3 15:20</div><a class="row-cta" href="#">查看 →</a>
        </div>
        <div class="hud-row">
          <span class="idx"><b>02</b></span>
          <div class="row-main"><div class="row-name">琥珀色仪表盘特写，扫描线质感</div>
          <div class="row-desc">gpt-image-2 · 1024×1024 · 生成耗时 16s</div></div>
          <span class="st ok"><span class="dot ok"></span>已完成</span>
          <div class="row-meta">10/3 15:12</div><a class="row-cta" href="#">查看 →</a>
        </div>
        <div class="hud-row">
          <span class="idx"><b>03</b></span>
          <div class="row-main"><div class="row-name">星云边缘的无人探测器</div>
          <div class="row-desc">gpt-image-2 · 排队中</div></div>
          <span class="st warn"><span class="dot warn"></span>生成中</span>
          <div class="row-meta">刚刚</div><a class="row-cta" href="#">查看 →</a>
        </div>
      </div>'''
page('hud-image.html', 'image', 'IMAGE//HISTORY', 'AI 生图', 'OpenAI 兼容生图供应商，异步生成并保存到历史', None,
     [("86","生成次数",""),("16s","平均耗时","")], image_body)

# ---------- 生图画布 ----------
canvas_body = '''
      <div style="min-height:420px;border:1px solid var(--divider);border-radius:2px;display:grid;place-items:center;position:relative;">
        <div style="text-align:center;color:var(--text-3);">
          <div style="font-family:var(--mono);font-size:11px;letter-spacing:.22em;color:var(--primary-hi);margin-bottom:10px;">CANVAS // READY</div>
          <div style="font-size:13px;">输入提示词生成图片后，画布将在此展开</div>
        </div>
      </div>
      <div class="toolbar" style="border-top:none;">
        <input class="search" style="max-width:none;" placeholder="描述画面，生成结果将铺满上方画布…">
        <button class="btn-primary">生成</button>
      </div>'''
page('hud-canvas.html', 'canvas', 'CANVAS//IMAGE', '生图画布', '生成结果直接铺满画布，支持在画布上继续调整', None, None, canvas_body)

# ---------- 任务中心 ----------
tasks_body = '''
      <div class="toolbar">
        <div class="filters"><a class="on" href="#">全部</a><a href="#">文件处理</a><a href="#">文档抽取</a><a href="#">生图</a><a href="#">向量重建</a></div>
        <div class="spacer"></div>
      </div>
      ''' + LBL + 'QUEUE // 最近任务</div>' + '''
      <div class="hud-list">
        <div class="hud-row">
          <span class="idx"><b>01</b></span>
          <div class="row-main"><div class="row-name">文件解析 · 一次侧接线图-扫描件.pdf</div>
          <div class="row-desc">视觉识别第 2 / 5 页 · 设备运维手册</div></div>
          <div class="row-meta">耗时 02:14 · 10/3 15:41</div>
          <span class="st warn"><span class="dot warn"></span>进行中</span>
        </div>
        <div class="hud-row">
          <span class="idx"><b>02</b></span>
          <div class="row-main"><div class="row-name">生图 · 星云边缘的无人探测器</div>
          <div class="row-desc">gpt-image-2 · 队列位置 1</div></div>
          <div class="row-meta">10/3 15:38</div>
          <span class="st warn"><span class="dot warn"></span>排队中</span>
        </div>
        <div class="hud-row">
          <span class="idx"><b>03</b></span>
          <div class="row-main"><div class="row-name">文件解析 · 油温记录表-10月.xlsx</div>
          <div class="row-desc">嵌入生成失败 · Ollama 连接超时</div></div>
          <div class="row-meta">10/2 22:14</div>
          <span class="st bad"><span class="dot bad"></span>失败</span>
          <a class="row-cta" href="#" style="color:var(--text-2);border-color:var(--line)">重试</a>
        </div>
        <div class="hud-row">
          <span class="idx"><b>04</b></span>
          <div class="row-main"><div class="row-name">向量重建 · 设备运维手册</div>
          <div class="row-desc">24 个文件 · 全部完成</div></div>
          <div class="row-meta">耗时 04:41 · 10/2 20:02</div>
          <span class="st ok"><span class="dot ok"></span>已完成</span>
        </div>
      </div>'''
page('hud-tasks.html', 'tasks', 'TASKS//QUEUE', '任务中心', '文件处理、抽取、生图与向量重建的统一任务队列', None,
     [("2","进行中",""),("18","今日完成",""),("1","失败","")], tasks_body)

# ---------- 知识缺口 ----------
gap_body = '''
      <div class="readouts">
        <div class="readout"><div class="val">12</div><div class="lab">近 7 天样本</div></div>
        <div class="readout"><div class="val">7</div><div class="lab">低置信回答</div></div>
        <div class="readout"><div class="val">3</div><div class="lab">无证据拒答</div></div>
        <div class="readout"><div class="val">2</div><div class="lab">被点踩</div></div>
      </div>
      <div class="toolbar"><h1 style="font-size:16px;font-weight:600;">待改进样本</h1><div class="spacer"></div>
        <div class="filters"><a class="on" href="#">近 7 天</a><a href="#">近 30 天</a></div></div>
      <div class="hud-list">
        <div class="hud-row">
          <span class="idx"><b>01</b></span>
          <div class="row-main"><div class="row-name">"备用电源切换的响应时间要求？"</div>
          <div class="row-desc">知识库未覆盖 · 建议补充《备用电源切换规程》</div></div>
          <div class="row-meta">拒答 · 10/3 09:12</div>
        </div>
        <div class="hud-row">
          <span class="idx"><b>02</b></span>
          <div class="row-main"><div class="row-name">"油温异常的处理流程？"</div>
          <div class="row-desc">证据覆盖率 38% · 置信度低</div></div>
          <div class="row-meta">低置信 · 10/2 20:44</div>
        </div>
        <div class="hud-row">
          <span class="idx"><b>03</b></span>
          <div class="row-main"><div class="row-name">"去年售前方案的中标率？"</div>
          <div class="row-desc">用户点踩 · 引用了过期方案</div></div>
          <div class="row-meta">无帮助 · 10/2 14:03</div>
        </div>
      </div>'''
page('hud-knowledgegap.html', 'knowledgegap', 'QUALITY//GAP-REPORT', '知识缺口', '低置信、拒答与被点踩的回答样本，知识库补充的依据', None,
     None, gap_body)

# ---------- RAG 评测 ----------
eval_body = '''
      <div class="readouts">
        <div class="readout"><div class="val">42</div><div class="lab">评测用例</div></div>
        <div class="readout"><div class="val">5</div><div class="lab">最近运行</div></div>
        <div class="readout"><div class="val">87%</div><div class="lab">平均命中率</div><div class="trend">+4%</div></div>
      </div>
      <div class="toolbar"><h1 style="font-size:16px;font-weight:600;">运行记录</h1><div class="spacer"></div>
        <button class="btn-primary">新建运行</button></div>
      <div class="hud-list">
        <div class="hud-row">
          <span class="idx"><b>#12</b></span>
          <div class="row-main"><div class="row-name">全量用例 · 售前解决方案库</div>
          <div class="row-desc">42 用例 · 命中 87% · 引用命中 79%</div></div>
          <div class="row-meta">耗时 03:12 · 10/3 14:20</div>
          <span class="st ok"><span class="dot ok"></span>已完成</span>
          <a class="row-cta" href="#">报告 →</a>
        </div>
        <div class="hud-row">
          <span class="idx"><b>#11</b></span>
          <div class="row-main"><div class="row-name">抽查 · 设备运维手册</div>
          <div class="row-desc">18 用例 · 命中 83% · 引用命中 72%</div></div>
          <div class="row-meta">耗时 01:44 · 10/2 09:30</div>
          <span class="st ok"><span class="dot ok"></span>已完成</span>
          <a class="row-cta" href="#">报告 →</a>
        </div>
      </div>'''
page('hud-eval.html', 'eval', 'EVAL//RUNS', 'RAG 评测', '维护测试用例，定期运行并跟踪命中率变化', '新建运行',
     None, eval_body)

# ---------- RBAC ----------
rbac_body = '''
      ''' + LBL + 'ROLES // 2</div>' + '''
      <div class="hud-list" style="margin-bottom:30px;">
        <div class="hud-row">
          <div class="row-main"><div class="row-name">平台管理员 <span class="mono" style="font-size:11px;color:var(--text-3);margin-left:8px;">admin</span></div>
          <div class="row-desc">全部菜单 · 用户 2 人</div></div>
        </div>
        <div class="hud-row">
          <div class="row-main"><div class="row-name">普通用户 <span class="mono" style="font-size:11px;color:var(--text-3);margin-left:8px;">user</span></div>
          <div class="row-desc">业务菜单 · 用户 5 人</div></div>
        </div>
      </div>
      ''' + LBL + 'USERS // 7</div>' + '''
      <div class="hud-list">
        <div class="hud-row">
          <span class="idx"><b>01</b></span>
          <div class="row-main"><div class="row-name">wujo</div>
          <div class="row-desc">平台管理员 · 上次登录 今天 13:20</div></div>
          <span class="st ok"><span class="dot ok"></span>在线</span>
          <a class="row-cta" href="#">管理 →</a>
        </div>
        <div class="hud-row">
          <span class="idx"><b>02</b></span>
          <div class="row-main"><div class="row-name">admin</div>
          <div class="row-desc">平台管理员 · 上次登录 昨天 18:02</div></div>
          <span class="st idle"><span class="dot idle"></span>离线</span>
          <a class="row-cta" href="#">管理 →</a>
        </div>
        <div class="hud-row">
          <span class="idx"><b>03</b></span>
          <div class="row-main"><div class="row-name">uinebula</div>
          <div class="row-desc">普通用户 · 上次登录 3 天前</div></div>
          <span class="st idle"><span class="dot idle"></span>离线</span>
          <a class="row-cta" href="#">管理 →</a>
        </div>
      </div>'''
page('hud-rbac.html', 'rbac', 'RBAC//ROLES+USERS', '权限管理', '角色、菜单授权与用户角色分配', None, None, rbac_body)

# ---------- 模型设置 ----------
settings_body = '''
      <div class="toolbar"><h1 style="font-size:16px;font-weight:600;">模型供应商</h1><div class="spacer"></div>
        <button class="btn-primary">＋ 新增供应商</button></div>
      <div class="hud-list">
        <div class="hud-row">
          <span class="idx"><b>01</b></span>
          <div class="row-main"><div class="row-name">GPT <span class="st-tag st-ok">● 使用中</span></div>
          <div class="row-desc">对话 gpt-6-sol · 生图 gpt-image-2 · https://rillapi.com</div></div>
          <div class="row-meta">创建人 —</div>
          <a class="row-cta" href="#">编辑 →</a>
        </div>
        <div class="hud-row">
          <span class="idx"><b>02</b></span>
          <div class="row-main"><div class="row-name">测试</div>
          <div class="row-desc">对话 11 · 生图 gpt-image-2 · admin 创建</div></div>
          <div class="row-meta">创建人 admin</div>
          <a class="row-cta" href="#">编辑 →</a>
        </div>
      </div>
      ''' + LBL + 'EMBEDDING // 本地 Ollama</div>' + '''
      <div class="hud-row">
        <div class="row-main"><div class="row-name">bge-m3 <span class="st-tag st-ok">● 在线</span></div>
        <div class="row-desc">http://localhost:11434 · 1024 维 · 知识库切片与召回向量化</div></div>
      </div>'''
page('hud-settings.html', 'settings', 'MODEL//PROVIDERS', '模型设置', '统一管理对话、生图与视觉模型的 API 地址、密钥和模型名', '＋ 新增供应商',
     None, settings_body)

# ---------- 系统健康 ----------
health_body = '''
      <div class="readouts">
        <div class="readout"><div class="val">6/6</div><div class="lab">服务在线</div></div>
        <div class="readout"><div class="val">32 天</div><div class="lab">持续运行</div></div>
        <div class="readout"><div class="val">78%</div><div class="lab">磁盘使用</div></div>
      </div>
      ''' + LBL + 'SERVICES // 全部在线</div>' + '''
      <div class="hud-list">
        <div class="hud-row"><div class="row-main"><div class="row-name">MySQL</div><div class="row-desc">rag_db · 连接正常</div></div>
          <div class="row-meta">延迟 2 ms</div><span class="st ok"><span class="dot ok"></span>正常</span></div>
        <div class="hud-row"><div class="row-main"><div class="row-name">Qdrant 向量库</div><div class="row-desc">rag_chunks · 12,048 点</div></div>
          <div class="row-meta">延迟 4 ms</div><span class="st ok"><span class="dot ok"></span>正常</span></div>
        <div class="hud-row"><div class="row-main"><div class="row-name">Embedding · bge-m3</div><div class="row-desc">本地 Ollama · 1024 维</div></div>
          <div class="row-meta">延迟 118 ms</div><span class="st ok"><span class="dot ok"></span>正常</span></div>
        <div class="hud-row"><div class="row-main"><div class="row-name">LLM · GPT / gpt-6-sol</div><div class="row-desc">https://rillapi.com</div></div>
          <div class="row-meta">延迟 340 ms</div><span class="st ok"><span class="dot ok"></span>正常</span></div>
        <div class="hud-row"><div class="row-main"><div class="row-name">磁盘 · uploads 分区</div><div class="row-desc">已用 78% · 剩余 42 GB</div></div>
          <div class="row-meta">—</div><span class="st warn"><span class="dot warn"></span>注意</span></div>
        <div class="hud-row"><div class="row-main"><div class="row-name">线程池</div><div class="row-desc">活跃 6 / 上限 32</div></div>
          <div class="row-meta">—</div><span class="st ok"><span class="dot ok"></span>正常</span></div>
      </div>'''
page('hud-health.html', 'health', 'SYS-HEALTH//OVERVIEW', '系统健康', 'MySQL、Qdrant、嵌入、大模型、磁盘与线程池的实时状态', None,
     None, health_body)

# ---------- 技能管理 ----------
skills_body = '''
      ''' + LBL + 'SKILL BINDINGS // 按智能体</div>' + '''
      <div class="hud-list" style="margin-bottom:30px;">
        <div class="hud-row">
          <div class="row-main"><div class="row-name">天气查询智能体</div>
          <div class="row-desc">search_city_location · get_weather</div></div>
          <span class="st ok"><span class="dot ok"></span>启用</span>
          <button class="row-ops">编辑</button>
        </div>
        <div class="hud-row">
          <div class="row-main"><div class="row-name">联网搜索智能体</div>
          <div class="row-desc">web_search</div></div>
          <span class="st ok"><span class="dot ok"></span>启用</span>
          <button class="row-ops">编辑</button>
        </div>
        <div class="hud-row">
          <div class="row-main"><div class="row-name">生图智能体</div>
          <div class="row-desc">generate_image</div></div>
          <span class="st ok"><span class="dot ok"></span>启用</span>
          <button class="row-ops">编辑</button>
        </div>
      </div>
      ''' + LBL + 'SKILL CATALOG // 部分目录</div>' + '''
      <div class="hud-list">
        <div class="hud-row">
          <div class="row-main"><div class="row-name">web_search</div>
          <div class="row-desc">联网搜索，返回标题、链接和摘要 · 搜索源已配置</div></div>
          <div class="row-meta">32 次调用 · 96%</div>
          <span class="st ok"><span class="dot ok"></span>启用</span>
        </div>
        <div class="hud-row">
          <div class="row-main"><div class="row-name">get_weather</div>
          <div class="row-desc">按经纬度查询当前天气与今日气温范围</div></div>
          <div class="row-meta">21 次调用 · 100%</div>
          <span class="st ok"><span class="dot ok"></span>启用</span>
        </div>
        <div class="hud-row">
          <div class="row-main"><div class="row-name">fetch_ai_news</div>
          <div class="row-desc">监控头部 AI 公司在 Hacker News 的近期动态</div></div>
          <div class="row-meta">4 次调用 · 100%</div>
          <span class="st ok"><span class="dot ok"></span>启用</span>
        </div>
      </div>'''
page('hud-skills.html', 'skills', 'SKILLS//CATALOG', '技能管理', '技能目录由代码自动同步，启停与智能体绑定在此配置', None,
     None, skills_body)

print('ALL PAGES GENERATED')
