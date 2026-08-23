import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { App } from './App'

describe('Career OS application shell', () => {
  it('keeps every primary decision destination reachable', () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: 'Career OS' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '今天' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: '我的规划' })).toHaveAttribute('href', '/plan')
    expect(screen.getByRole('link', { name: '机会池' })).toHaveAttribute('href', '/opportunities')
    expect(screen.getByRole('link', { name: '更新岗位库' })).toHaveAttribute('href', '/updates')
    expect(screen.getByRole('link', { name: '我的资料' })).toHaveAttribute('href', '/profile')
  })
})
