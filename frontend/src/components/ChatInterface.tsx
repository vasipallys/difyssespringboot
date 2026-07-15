// src/components/ChatInterface.tsx
import React, { useEffect, useRef, useState } from 'react';
import { useDifyStream } from '../hooks/useDifyStream';
import './ChatInterface.css';

export const ChatInterface: React.FC = () => {
  const [input, setInput] = useState('');
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const {
    messages,
    isStreaming,
    error,
    sendMessage,
    stopStream,
    conversationId,
    clearMessages,
  } = useDifyStream();

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!input.trim() || isStreaming) return;
    void sendMessage(input.trim());
    setInput('');
    // Reset textarea height
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto';
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit(e);
    }
  };

  const handleTextareaInput = (e: React.FormEvent<HTMLTextAreaElement>) => {
    const target = e.currentTarget;
    target.style.height = 'auto';
    target.style.height = Math.min(target.scrollHeight, 200) + 'px';
  };

  // Auto-scroll to bottom
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  return (
    <div className="chat-container">
      <header className="chat-header">
        <h1>Dify AI Chat</h1>
        <div className="header-actions">
          {conversationId && (
            <span className="conversation-id" title={conversationId}>
              Session: {conversationId.slice(0, 8)}...
            </span>
          )}
          <button className="clear-btn" onClick={clearMessages}>
            Clear
          </button>
        </div>
      </header>

      <div className="messages-container">
        {messages.length === 0 && (
          <div className="empty-state">
            <p>Start a conversation with Dify AI</p>
            <p className="hint">Type a message below and press Enter</p>
          </div>
        )}

        {messages.map((msg) => (
          <div
            key={msg.id}
            className={`message ${msg.role} ${msg.isStreaming ? 'streaming' : ''} ${
              msg.error ? 'error' : ''
            }`}
          >
            <div className="message-avatar">
              {msg.role === 'user' ? '👤' : '🤖'}
            </div>
            <div className="message-content">
              <div className="message-text">
                {msg.content ||
                  (msg.isStreaming ? <span className="typing">Thinking...</span> : '')}
                {msg.isStreaming && <span className="cursor">▌</span>}
              </div>
              {msg.error && <div className="message-error">Error: {msg.error}</div>}
              <div className="message-meta">{msg.timestamp.toLocaleTimeString()}</div>
            </div>
          </div>
        ))}

        {error && !messages.some((m) => m.error) && (
          <div className="global-error">
            <span>⚠️ {error}</span>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      <form className="input-area" onSubmit={handleSubmit}>
        <textarea
          ref={textareaRef}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          onInput={handleTextareaInput}
          placeholder="Type your message... (Shift+Enter for new line)"
          rows={1}
          disabled={isStreaming}
        />
        {isStreaming ? (
          <button type="button" className="stop-btn" onClick={stopStream}>
            ⏹ Stop
          </button>
        ) : (
          <button type="submit" className="send-btn" disabled={!input.trim()}>
            ➤ Send
          </button>
        )}
      </form>
    </div>
  );
};
