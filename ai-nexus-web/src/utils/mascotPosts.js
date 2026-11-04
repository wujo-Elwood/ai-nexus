// =====================================================================
// 吉祥物：工位 / 热点 / 巡逻路线
// posts：常驻工位（进页面即上岗）
// hotspots：互动热点（点击对应控件，吉祥物走过去做动作再回岗）
// TOURS：入场巡逻（进入页面后自动走到 2~3 个功能点旁各做一个动作，然后回家）
// 方案：docs/吉祥物系统方案.md §8
// =====================================================================

// 全站默认热点：任何页面点这些控件都有反应
export const DEFAULT_HOTSPOTS = [
  { selector: '.btn-primary', action: 'jump' },
  { selector: '.el-button--primary', action: 'cheer' },
  { selector: '.el-switch', action: 'spin' },
  { selector: '.search', action: 'inspect' },
  { selector: '.el-upload-dragger', action: 'backflip' },
  { selector: '.el-tabs__item', action: 'nod' }
]

// 全站兜底巡逻：页面没声明时用它
export const DEFAULT_TOUR = [
  { selector: '.btn-primary', action: 'jump' },
  { selector: '.el-table', action: 'inspect' }
]

export const POSTS = {
  // 知识库：搜索框聚焦凑近看
  '/kb': {
    hotspots: [
      { selector: '.search', action: 'inspect' }
    ]
  },
  // 模型设置：表格操作凑近看
  '/settings': {
    hotspots: [
      { selector: '.provider-table .el-button', action: 'inspect' }
    ]
  },
  // 系统健康：异常行凑近看
  '/health': {
    hotspots: [
      { selector: '.el-table', action: 'inspect' }
    ]
  },
  // 智能体：点行进入前扭动告别
  '/agents': {
    hotspots: [
      { selector: '.agent-entry .row-cta', action: 'shake' }
    ]
  }
}

// 入场巡逻路线：每站一个功能点 + 一个动作
export const TOURS = {
  '/kb': [
    { selector: '.readouts', action: 'nod' },
    { selector: '.search', action: 'inspect' },
    { selector: '.btn-primary', action: 'jump' }
  ],
  '/chat': [
    { selector: '.mode-card', action: 'inspect' },
    { selector: '.chat-input-panel .send-button', action: 'jump' }
  ],
  '/tasks': [
    { selector: '.rebuild-strip', action: 'push' },
    { selector: '.ledger', action: 'inspect' },
    { selector: '.task-table', action: 'roll' }
  ],
  '/extract': [
    { selector: '.upload-panel', action: 'backflip' },
    { selector: '.el-select', action: 'inspect' },
    { selector: '.btn-primary', action: 'jump' }
  ],
  '/agents': [
    { selector: '.agent-entry', action: 'nod' },
    { selector: '.agent-entry .row-cta', action: 'shake' }
  ],
  '/settings': [
    { selector: '.provider-table', action: 'inspect' },
    { selector: '.btn-primary', action: 'cheer' }
  ],
  '/stats': [
    { selector: '.stats-cards', action: 'nod' },
    { selector: '.trend-section', action: 'jump' }
  ],
  '/health': [
    { selector: '.el-table', action: 'inspect' },
    { selector: '.btn-primary', action: 'shake' }
  ],
  '/analysis': [
    { selector: '.upload-zone, .ghost-upload', action: 'backflip' },
    { selector: '.ms-input', action: 'inspect' }
  ],
  '/skills': [
    { selector: '.agent-rows', action: 'nod' },
    { selector: '.skill-table', action: 'inspect' }
  ],
  '/image': [
    { selector: '.el-textarea', action: 'inspect' },
    { selector: '.btn-primary', action: 'jump' }
  ],
  '/image-canvas': [
    { selector: '.canvas-stage', action: 'spin' },
    { selector: '.gen-row', action: 'jump' }
  ],
  '/rbac': [
    { selector: '.tabs', action: 'nod' },
    { selector: '.el-table', action: 'inspect' }
  ],
  '/knowledge-gaps': [
    { selector: '.ledger', action: 'shake' },
    { selector: '.table, .el-table', action: 'inspect' }
  ],
  '/eval': [
    { selector: '.add-row', action: 'nod' },
    { selector: '.el-table', action: 'inspect' }
  ],
  '/profile': [
    { selector: '.form-line, .el-form', action: 'inspect' },
    { selector: '.btn-primary', action: 'cheer' }
  ],
  '/change-password': [
    { selector: '.form-line, .el-form', action: 'inspect' }
  ],
  '/file': [
    { selector: '.el-upload-dragger, .upload-panel', action: 'backflip' },
    { selector: '.el-table', action: 'inspect' }
  ],
  '/agent-tools': [
    { selector: '.ms-input, .chat-input', action: 'inspect' }
  ],
  '/insights': [
    { selector: '.el-form', action: 'inspect' },
    { selector: '.btn-primary', action: 'cheer' }
  ]
}
