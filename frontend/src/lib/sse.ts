// src/lib/sse.ts
// Minimal, spec-compliant-enough SSE frame parser for fetch + ReadableStream.
// EventSource only supports GET, so POST-based SSE needs manual parsing.

export interface SseFrame {
  event?: string;
  data: string;
  id?: string;
}

/**
 * Creates a stateful parser. Feed it decoded text chunks (in arrival order);
 * it returns the complete SSE frames found so far and buffers partial frames
 * across chunk boundaries.
 */
export function createSseParser(): (chunk: string) => SseFrame[] {
  let buffer = '';

  return function feed(chunk: string): SseFrame[] {
    buffer += chunk;
    // Normalize CRLF; a trailing lone \r is kept until its \n arrives.
    buffer = buffer.replace(/\r\n/g, '\n');

    const frames: SseFrame[] = [];
    let separatorIndex: number;

    while ((separatorIndex = buffer.indexOf('\n\n')) !== -1) {
      const block = buffer.slice(0, separatorIndex);
      buffer = buffer.slice(separatorIndex + 2);

      const frame = parseBlock(block);
      if (frame) {
        frames.push(frame);
      }
    }

    return frames;
  };
}

function parseBlock(block: string): SseFrame | null {
  let event: string | undefined;
  let id: string | undefined;
  const dataLines: string[] = [];

  for (const line of block.split('\n')) {
    if (!line || line.startsWith(':')) continue; // empty line or comment

    const colonIndex = line.indexOf(':');
    const field = colonIndex === -1 ? line : line.slice(0, colonIndex);
    let value = colonIndex === -1 ? '' : line.slice(colonIndex + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    switch (field) {
      case 'event':
        event = value;
        break;
      case 'data':
        dataLines.push(value);
        break;
      case 'id':
        id = value;
        break;
      default:
        // Ignore unknown fields (e.g. retry)
        break;
    }
  }

  if (dataLines.length === 0 && !event) return null;
  return { event, data: dataLines.join('\n'), id };
}
