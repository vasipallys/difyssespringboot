// src/hooks/useDifyStream.test.ts
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useDifyStream } from './useDifyStream';

function sseResponse(sseBody: string, status = 200): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      // Emit in small chunks to exercise cross-chunk buffering
      for (let i = 0; i < sseBody.length; i += 17) {
        controller.enqueue(encoder.encode(sseBody.slice(i, i + 17)));
      }
      controller.close();
    },
  });
  return new Response(stream, {
    status,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}

describe('useDifyStream', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    fetchMock.mockReset();
  });

  it('accumulates streamed tokens into the assistant message', async () => {
    fetchMock.mockResolvedValue(
      sseResponse(
        'event: message\ndata: {"event":"message","answer":"Hello","conversation_id":"conv-1"}\n\n' +
          'event: message\ndata: {"event":"message","answer":" world"}\n\n' +
          'event: message_end\ndata: {"event":"message_end","conversation_id":"conv-1"}\n\n'
      )
    );

    const { result } = renderHook(() => useDifyStream());

    await act(async () => {
      await result.current.sendMessage('Hi there');
    });

    expect(result.current.messages).toHaveLength(2);
    expect(result.current.messages[0].role).toBe('user');
    expect(result.current.messages[0].content).toBe('Hi there');
    expect(result.current.messages[1].role).toBe('assistant');
    expect(result.current.messages[1].content).toBe('Hello world');
    expect(result.current.messages[1].isStreaming).toBe(false);
    expect(result.current.conversationId).toBe('conv-1');
    expect(result.current.isStreaming).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('sends the message and conversation id to the backend', async () => {
    fetchMock.mockResolvedValue(
      sseResponse('data: {"event":"message_end","conversation_id":"conv-7"}\n\n')
    );

    const { result } = renderHook(() => useDifyStream());

    await act(async () => {
      await result.current.sendMessage('first');
    });

    await waitFor(() => expect(result.current.conversationId).toBe('conv-7'));

    fetchMock.mockResolvedValue(
      sseResponse('data: {"event":"message_end","conversation_id":"conv-7"}\n\n')
    );

    await act(async () => {
      await result.current.sendMessage('second');
    });

    const secondCallBody = JSON.parse(fetchMock.mock.calls[1][1].body as string);
    expect(secondCallBody.message).toBe('second');
    expect(secondCallBody.conversationId).toBe('conv-7');

    const firstCall = fetchMock.mock.calls[0];
    expect(firstCall[0]).toContain('/chat/stream');
    expect(firstCall[1].method).toBe('POST');
  });

  it('handles Dify error events', async () => {
    fetchMock.mockResolvedValue(
      sseResponse('event: error\ndata: {"event":"error","error":"Model overloaded"}\n\n')
    );

    const { result } = renderHook(() => useDifyStream());

    await act(async () => {
      await result.current.sendMessage('Hi');
    });

    expect(result.current.error).toBe('Model overloaded');
    expect(result.current.messages[1].error).toBe('Model overloaded');
    expect(result.current.messages[1].isStreaming).toBe(false);
  });

  it('sets error state when the HTTP request fails', async () => {
    fetchMock.mockResolvedValue(
      new Response('Internal Server Error', { status: 500 })
    );

    const { result } = renderHook(() => useDifyStream());

    await act(async () => {
      await result.current.sendMessage('Hi');
    });

    expect(result.current.error).toContain('HTTP 500');
    expect(result.current.messages[1].error).toContain('HTTP 500');
    expect(result.current.isStreaming).toBe(false);
  });

  it('clearMessages resets messages, conversation and error', async () => {
    fetchMock.mockResolvedValue(
      sseResponse('data: {"event":"message","answer":"x","conversation_id":"c1"}\n\n')
    );

    const { result } = renderHook(() => useDifyStream());

    await act(async () => {
      await result.current.sendMessage('Hi');
    });

    expect(result.current.messages.length).toBeGreaterThan(0);

    act(() => {
      result.current.clearMessages();
    });

    expect(result.current.messages).toHaveLength(0);
    expect(result.current.conversationId).toBeNull();
    expect(result.current.error).toBeNull();
  });

  it('marks the assistant message as stopped when aborted', async () => {
    fetchMock.mockImplementation(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => {
            const err = new Error('aborted');
            err.name = 'AbortError';
            reject(err);
          });
        })
    );

    const { result } = renderHook(() => useDifyStream());

    let pending: Promise<void>;
    act(() => {
      pending = result.current.sendMessage('Hi');
    });

    await waitFor(() => expect(result.current.isStreaming).toBe(true));

    act(() => {
      result.current.stopStream();
    });

    await act(async () => {
      await pending;
    });

    expect(result.current.isStreaming).toBe(false);
    expect(result.current.messages[1].content).toContain('[Stopped]');
  });
});
