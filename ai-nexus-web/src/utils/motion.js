import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

gsap.registerPlugin(ScrollTrigger)

let motionTimer = null

// 初始化页面动效
export function initPageMotion(router) {
  // 第1步：页面首次加载后执行一次动效
  runMotionAfterRender()
  // 第2步：路由切换完成后重新执行动效
  router.afterEach(() => {
    runMotionAfterRender()
  })
}

// 等待页面渲染完成后执行动效
function runMotionAfterRender() {
  // 第1步：清理上一次延迟任务
  window.clearTimeout(motionTimer)
  // 第2步：等 Vue 和浏览器完成渲染后再查找元素
  motionTimer = window.setTimeout(() => {
    requestAnimationFrame(() => {
      playPageMotion()
    })
  }, 80)
}

// 播放当前页面的入场和滚动动效
function playPageMotion() {
  // 第1步：尊重用户系统里的减少动态效果设置
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    // 第2步：只处理真实存在的动效元素，避免空选择器产生告警
    const reducedMotionElements = gsap.utils.toArray('.page-hero, .motion-card, .motion-image')
    if (reducedMotionElements.length > 0) {
      gsap.set(reducedMotionElements, { opacity: 1, clearProps: 'transform' })
    }
    return
  }
  // 第2步：清理旧页面遗留的滚动触发器
  ScrollTrigger.getAll().forEach(trigger => trigger.kill())
  // 第3步：分开查询页面标题、卡片和图片动效元素
  const heroElements = gsap.utils.toArray('.page-hero')
  const cardElements = gsap.utils.toArray('.motion-card')
  const imageElements = gsap.utils.toArray('.motion-image')
  // 第4步：合并真实存在的动效元素
  const motionElements = [...heroElements, ...cardElements, ...imageElements]
  // 第5步：页面没有动效元素时直接结束，避免 GSAP 输出空目标告警
  if (motionElements.length === 0) {
    return
  }
  // 第6步：先强制内容可见，避免动画中断导致页面空白
  gsap.set(motionElements, { opacity: 1 })
  // 第7步：播放页面标题区域的轻量入场效果
  if (heroElements.length > 0) {
    gsap.from(heroElements, {
      y: 18,
      duration: 0.45,
      ease: 'power2.out',
      clearProps: 'transform'
    })
  }
  // 第8步：播放卡片的轻量渐进入场效果
  if (cardElements.length > 0) {
    gsap.from(cardElements, {
      y: 20,
      duration: 0.42,
      ease: 'power2.out',
      stagger: 0.035,
      clearProps: 'transform'
    })
  }
  // 第9步：让图片型区域在滚动时轻微放大并淡入
  imageElements.forEach(imageElement => {
    gsap.from(imageElement, {
      scale: 0.96,
      ease: 'none',
      clearProps: 'transform',
      scrollTrigger: {
        trigger: imageElement,
        start: 'top 92%',
        end: 'bottom 18%',
        scrub: true
      }
    })
  })
  // 第10步：刷新滚动计算，避免页面刚加载时高度不稳定
  ScrollTrigger.refresh()
}
