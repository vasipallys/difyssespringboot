// src/hooks/useDifyStream.ts
import { useCallback, useRef, useState } from 'react';
import { ChatMessage, DifyStreamEvent } from '../types/dify';
import { createSseParser } from '../lib/sse';

const API_BASE = import.meta.env.VITE_API_URL || 'http://localhost:8080/api';

export interface UseDifyStreamReturn {
  messages: ChatMessage[];
  isStreaming: boolean;
  error: string | null;
  sendMessage: (message: string, inputs?: Record<string, unknown>) => Promise<void>;
  stopStream: () => void;
  conversationId: string | null;
  clearMessages: () => void;
}

const generateId = () => Math.random().toString(36).substring(2, 15);

export const useDifyStream = (): UseDifyStreamReturn => {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conversationId, setConversationId] = useState<string | null>(null);

  const abortControllerRef = useRef<AbortController | null>(null);

  const stopStream = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
    }
    setIsStreaming(false);
  }, []);

  const handleEvent = useCallback(
    (eventType: string, data: DifyStreamEvent, assistantId: string) => {
      switch (eventType) {
        case 'message': {
          // Token chunk from LLM
          if (data.answer) {
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantId
                  ? { ...msg, content: msg.content + data.answer }
                  : msg
              )
            );
          }
          if (data.conversation_id) {
            setConversationId(data.conversation_id);
          }
          break;
        }

        case 'agent_message': {
          // Agent reasoning step - could be surfaced as a thought process
          if (data.answer) {
            setMessages((prev) =>
              prev.map((msg) =>
                msg.id === assistantId
                  ? { ...msg, content: msg.content + data.answer }
                  : msg
              )
            );
          }
          break;
        }

        case 'workflow_finished':
        case 'message_end': {
          if (data.conversation_id) {
            setConversationId(data.conversation_id);
          }
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId ? { ...msg, isStreaming: false } : msg
            )
          );
          break;
        }

        case 'error': {
          const message = data.error || 'Unknown error from Dify';
          setError(message);
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId
                ? { ...msg, isStreaming: false, error: message }
                : msg
            )
          );
          break;
        }

        case 'ping': {
          // Keep-alive, ignore
          break;
        }

        default: {
          // tool_call, tool_response, node_started, node_finished, workflow_started...
          // Not rendered in this UI; useful for debugging.
          console.debug('[SSE] Unhandled event type:', eventType, data);
        }
      }
    },
    []
  );

  const sendMessage = useCallback(
    async (message: string, inputs?: Record<string, unknown>) => {
      // Stop any existing stream
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
      setError(null);
      setIsStreaming(true);

      const userMessage: ChatMessage = {
        id: generateId(),
        role: 'user',
        content: message,
        timestamp: new Date(),
      };

      const assistantId = generateId();
      const assistantMessage: ChatMessage = {
        id: assistantId,
        role: 'assistant',
        content: '',
        isStreaming: true,
        timestamp: new Date(),
      };

      setMessages((prev) => [...prev, userMessage, assistantMessage]);

      const controller = new AbortController();
      abortControllerRef.current = controller;

      try {
        const response = await fetch(`${API_BASE}/chat/stream`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-Request-Id': generateId(),
          },
          body: JSON.stringify({
            message,
            inputs: inputs || {},
            conversationId: conversationId || undefined,
          }),
          signal: controller.signal,
        });

        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(`HTTP ${response.status}: ${errorText}`);
        }

        if (!response.body) {
          throw new Error('No response body received');
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        const parse = createSseParser();

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          for (const frame of parse(decoder.decode(value, { stream: true }))) {
            if (!frame.data) continue;
            try {
              const eventData: DifyStreamEvent = JSON.parse(frame.data);
              handleEvent(frame.event || eventData.event, eventData, assistantId);
            } catch (e) {
              console.error('Failed to parse SSE data:', frame.data, e);
            }
          }
        }

        // Mark streaming as complete
        setMessages((prev) =>
          prev.map((msg) =>
            msg.id === assistantId ? { ...msg, isStreaming: false } : msg
          )
        );
      } catch (err) {
        const e = err as Error;
        if (e.name === 'AbortError') {
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId
                ? { ...msg, isStreaming: false, content: msg.content + '\n[Stopped]' }
                : msg
            )
          );
        } else {
          console.error('Stream error:', e);
          setError(e.message);
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantId
                ? { ...msg, isStreaming: false, error: e.message }
                : msg
            )
          );
        }
      } finally {
        setIsStreaming(false);
        abortControllerRef.current = null;
      }
    },
    [conversationId, handleEvent]
  );

  const clearMessages = useCallback(() => {
    setMessages([]);
    setConversationId(null);
    setError(null);
  }, []);

  return {
    messages,
    isStreaming,
    error,
    sendMessage,
    stopStream,
    conversationId,
    clearMessages,
  };
};
