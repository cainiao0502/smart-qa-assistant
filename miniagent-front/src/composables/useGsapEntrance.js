import { onBeforeUnmount } from 'vue'
import gsap from 'gsap'

/**
 * useGsapEntrance - GSAP 入场动画 composable
 *
 * 提供 staggerIn（级联入场）和 revealIn（单元素入场）两个方法。
 * 内置 prefers-reduced-motion 降级和自动 cleanup。
 *
 * 只动画 transform 和 opacity，遵循硬件加速规则。
 */
export function useGsapEntrance() {
  const tweens = []

  function prefersReducedMotion() {
    return window.matchMedia('(prefers-reduced-motion: reduce)').matches
  }

  function staggerIn(targets, options = {}) {
    if (!targets || (Array.isArray(targets) && targets.length === 0)) return
    if (prefersReducedMotion()) return

    const {
      y = 24,
      opacity = 0,
      duration = 0.6,
      stagger = 0.08,
      ease = 'power3.out',
      delay = 0
    } = options

    const tween = gsap.from(targets, {
      y,
      opacity,
      duration,
      stagger,
      ease,
      delay
    })
    tweens.push(tween)
    return tween
  }

  function revealIn(target, options = {}) {
    if (!target) return
    if (prefersReducedMotion()) return

    const {
      y = 20,
      opacity = 0,
      duration = 0.55,
      ease = 'power3.out',
      delay = 0
    } = options

    const tween = gsap.from(target, {
      y,
      opacity,
      duration,
      ease,
      delay
    })
    tweens.push(tween)
    return tween
  }

  function killAll() {
    tweens.forEach((tween) => {
      if (tween && typeof tween.kill === 'function') {
        tween.kill()
      }
    })
    tweens.length = 0
  }

  onBeforeUnmount(killAll)

  return {
    staggerIn,
    revealIn,
    killAll
  }
}