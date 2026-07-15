// src/types/dify.ts

export interface DifyStreamEvent {
  event: string;
  task_id?: string;
  id?: string;
  message_id?: string;
  conversation_id?: string;
  answer?: string;
  created_at?: number;
  error?: string;
  data?: Record<string, unknown>;
  tool_call_id?: string;
  tool_call?: Record<string, unknown>;
  tool_response?: Record<string, unknown>;
  workflow_run_id?: string;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  isStreaming?: boolean;
  error?: string;
  timestamp: Date;
}

export interface ChatRequest {
  message: string;
  conversationId?: string;
  inputs?: Record<string, unknown>;
}
