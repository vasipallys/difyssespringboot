// src/lib/sse.test.ts
import { describe, expect, it } from 'vitest';
import { createSseParser } from './sse';

describe('createSseParser', () => {
  it('parses a single complete frame', () => {
    const parse = createSseParser();
    const frames = parse('event: message\ndata: {"answer":"hi"}\n\n');

    expect(frames).toHaveLength(1);
    expect(frames[0].event).toBe('message');
    expect(frames[0].data).toBe('{"answer":"hi"}');
  });

  it('parses multiple frames in one chunk', () => {
    const parse = createSseParser();
    const frames = parse(
      'event: message\ndata: {"a":1}\n\nevent: message_end\ndata: {"b":2}\n\n'
    );

    expect(frames).toHaveLength(2);
    expect(frames[0].event).toBe('message');
    expect(frames[1].event).toBe('message_end');
  });

  it('buffers partial frames across chunk boundaries', () => {
    const parse = createSseParser();

    expect(parse('event: mess')).toHaveLength(0);
    expect(parse('age\ndata: {"answer":')).toHaveLength(0);

    const frames = parse('"hello"}\n\n');
    expect(frames).toHaveLength(1);
    expect(frames[0].event).toBe('message');
    expect(frames[0].data).toBe('{"answer":"hello"}');
  });

  it('handles CRLF line endings', () => {
    const parse = createSseParser();
    const frames = parse('event: message\r\ndata: {"x":1}\r\n\r\n');

    expect(frames).toHaveLength(1);
    expect(frames[0].event).toBe('message');
    expect(frames[0].data).toBe('{"x":1}');
  });

  it('joins multi-line data fields with newlines', () => {
    const parse = createSseParser();
    const frames = parse('data: line1\ndata: line2\n\n');

    expect(frames).toHaveLength(1);
    expect(frames[0].data).toBe('line1\nline2');
  });

  it('captures the id field', () => {
    const parse = createSseParser();
    const frames = parse('id: 42\nevent: message\ndata: {}\n\n');

    expect(frames[0].id).toBe('42');
  });

  it('ignores comment lines', () => {
    const parse = createSseParser();
    const frames = parse(': keep-alive\n\ndata: {"real":true}\n\n');

    expect(frames).toHaveLength(1);
    expect(frames[0].data).toBe('{"real":true}');
  });

  it('returns nothing for incomplete input', () => {
    const parse = createSseParser();
    expect(parse('data: {"unfinished":true}\n')).toHaveLength(0);
  });
});
