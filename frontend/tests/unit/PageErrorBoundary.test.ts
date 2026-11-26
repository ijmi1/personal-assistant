import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { describe, expect, it } from 'vitest'
import PageErrorBoundary from '../../src/PageErrorBoundary.vue'

describe('PageErrorBoundary', () => {
  it('offers recovery when navigation fails and shows content after navigation succeeds', async () => {
    const wrapper = mount(PageErrorBoundary, {
      props: { navigationFailed: false, routeKey: '/' },
      slots: { default: () => h('p', '资料') },
    })
    await wrapper.setProps({ navigationFailed: true })
    expect(wrapper.get('[role="alert"]').text()).toContain('页面暂时无法打开')
    expect(wrapper.emitted('failure')).toEqual([['navigation']])
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('reload')).toHaveLength(1)
    await wrapper.setProps({ navigationFailed: false, routeKey: '/documents' })
    expect(wrapper.text()).toBe('资料')
  })

  it('reports only the kind of a render error', async () => {
    const failing = defineComponent({ render() { throw new Error('private document data') } })
    const wrapper = mount(PageErrorBoundary, {
      props: { navigationFailed: false, routeKey: '/reader/123' },
      slots: { default: () => h(failing) },
    })
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[role="alert"]').text()).not.toContain('private document data')
    expect(wrapper.emitted('failure')).toEqual([['render']])
  })
})
