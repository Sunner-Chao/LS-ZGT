/**
 * SSE (Server-Sent Events) 解析工具
 * 统一处理 answer / sources / loading / thinking / error / done 等事件类型
 */

export interface SSEMessage {
  type: 'answer' | 'sources' | 'loading' | 'thinking' | 'error' | 'done'
  content?: string
  sources?: any[]
  [key: string]: any
}

export interface SSEParseOptions {
  onAnswer?: (content: string) => void
  onSources?: (sources: any[]) => void
  onLoading?: (content: string) => void
  onThinking?: (content: string) => void
  onError?: (content: string) => void
  onDone?: () => void
}

/**
 * 将 SSE 数据行数组解析为消息对象
 * @param lines 未经处理的原始行数组
 * @returns 解析后的 SSEMessage | null（解析失败返回 null）
 */
export function parseSSELine(line: string): SSEMessage | null {
  let text = line.trim()
  if (!text) return null

  // 支持 "data: {...}" 和直接 JSON 两种格式
  // 循环剥离，以防后端发送 "data:data: {...}"
  while (text.startsWith('data:')) {
    text = text.slice(5).trim()
  }
  if (!text) return null
  try {
    return JSON.parse(text) as SSEMessage
  } catch {
    return null
  }
}

/**
 * 增量式 SSE 解析器（复用 buffer，适合流式场景）
 *
 * @example
 * const parser = new SSEParser({
 *   onAnswer: (c) => { answerText += c },
 *   onSources: (s) => { sources = s },
 *   onError: (e) => { console.error(e) },
 * })
 * for (const chunk of streamedChunks) {
 *   parser.push(chunk)
 * }
 * parser.end()
 */
export class SSEParser {
  private buffer = ''
  private done = false

  constructor(private handlers: SSEParseOptions = {}) {}

  /**
   * 写入一个数据块（通常是 fetch 流的一个 Uint8Array chunk）
   */
  push(chunk: Uint8Array, decoder = new TextDecoder()): void {
    if (this.done) return
    this.buffer += decoder.decode(chunk, { stream: true })
    this._flush()
  }

  /**
   * 写入一个已完成解码的字符串块
   */
  pushString(chunk: string): void {
    if (this.done) return
    this.buffer += chunk
    this._flush()
  }

  private _flush(): void {
    const lines = this.buffer.split('\n')
    this.buffer = lines.pop() ?? ''

    for (const line of lines) {
      const msg = parseSSELine(line)
      if (!msg) continue
      this._dispatch(msg)
    }
  }

  private _dispatch(msg: SSEMessage): void {
    switch (msg.type) {
      case 'answer':
        this.handlers.onAnswer?.(msg.content ?? '')
        break
      case 'sources':
        this.handlers.onSources?.(msg.sources ?? [])
        break
      case 'loading':
        this.handlers.onLoading?.(msg.content ?? '')
        break
      case 'thinking':
        this.handlers.onThinking?.(msg.content ?? '')
        break
      case 'error':
        this.handlers.onError?.(msg.content ?? msg.error ?? '未知错误')
        break
      case 'done':
        this.handlers.onDone?.()
        break
    }
  }

  /** 标记流结束，处理 buffer 中残留数据 */
  end(): void {
    this.done = true
    this._flush()
  }

  /** 取消解析 */
  abort(): void {
    this.done = true
    this.buffer = ''
  }
}

/**
 * 封装 fetch SSE 流，返回一个 SSEParser 实例
 * 自动处理网络错误并在 error handler 中报告
 *
 * @param url  请求 URL
 * @param options fetch 选项（method, headers, body 等）
 * @param handlers  事件回调
 * @returns SSEParser 实例（用于 abort 等控制）
 */
export async function fetchSSE(
  url: string,
  options: RequestInit = {},
  handlers: SSEParseOptions = {}
): Promise<SSEParser> {
  const response = await fetch(url, options)

  if (!response.ok) {
    handlers.onError?.(`HTTP ${response.status}: ${response.statusText}`)
    const parser = new SSEParser(handlers)
    parser.end()
    return parser
  }

  if (!response.body) {
    handlers.onError?.('响应体为空')
    const parser = new SSEParser(handlers)
    parser.end()
    return parser
  }

  const parser = new SSEParser(handlers)
  const reader = response.body.getReader()

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      parser.push(value)
    }
  } finally {
    parser.end()
    reader.releaseLock()
  }

  return parser
}
