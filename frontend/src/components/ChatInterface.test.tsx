// src/components/ChatInterface.test.tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatInterface } from './ChatInterface';
import type { UseDifyStreamReturn } from '../hooks/useDifyStream';
import { useDifyStream } from '../hooks/useDifyStream';

vi.mock('../hooks/useDifyStream');

const mockedUseDifyStream = vi.mocked(useDifyStream);

function hookState(overrides: Partial<UseDifyStreamReturn> = {}): UseDifyStreamReturn {
  return {
    messages: [],
    isStreaming: false,
    error: null,
    sendMessage: vi.fn().mockResolvedValue(undefined),
    stopStream: vi.fn(),
    conversationId: null,
    clearMessages: vi.fn(),
    ...overrides,
  };
}

describe('ChatInterface', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the empty state when there are no messages', () => {
    mockedUseDifyStream.mockReturnValue(hookState());
    render(<ChatInterface />);

    expect(screen.getByText('Start a conversation with Dify AI')).toBeInTheDocument();
  });

  it('renders user and assistant messages', () => {
    mockedUseDifyStream.mockReturnValue(
      hookState({
        messages: [
          {
            id: '1',
            role: 'user',
            content: 'Hello there',
            timestamp: new Date(),
          },
          {
            id: '2',
            role: 'assistant',
            content: 'Hi! How can I help?',
            timestamp: new Date(),
          },
        ],
      })
    );
    render(<ChatInterface />);

    expect(screen.getByText('Hello there')).toBeInTheDocument();
    expect(screen.getByText('Hi! How can I help?')).toBeInTheDocument();
  });

  it('sends the trimmed message and clears the input on submit', async () => {
    const state = hookState();
    mockedUseDifyStream.mockReturnValue(state);
    render(<ChatInterface />);

    const textarea = screen.getByPlaceholderText(/Type your message/);
    await userEvent.type(textarea, '  What is SSE?  ');
    await userEvent.click(screen.getByRole('button', { name: /Send/ }));

    expect(state.sendMessage).toHaveBeenCalledWith('What is SSE?');
    expect(textarea).toHaveValue('');
  });

  it('sends the message when Enter is pressed', async () => {
    const state = hookState();
    mockedUseDifyStream.mockReturnValue(state);
    render(<ChatInterface />);

    const textarea = screen.getByPlaceholderText(/Type your message/);
    await userEvent.type(textarea, 'Hello{Enter}');

    expect(state.sendMessage).toHaveBeenCalledWith('Hello');
  });

  it('disables the send button when input is empty', () => {
    mockedUseDifyStream.mockReturnValue(hookState());
    render(<ChatInterface />);

    expect(screen.getByRole('button', { name: /Send/ })).toBeDisabled();
  });

  it('shows a stop button while streaming and wires it to stopStream', async () => {
    const state = hookState({ isStreaming: true });
    mockedUseDifyStream.mockReturnValue(state);
    render(<ChatInterface />);

    const stopButton = screen.getByRole('button', { name: /Stop/ });
    expect(stopButton).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Send/ })).not.toBeInTheDocument();

    await userEvent.click(stopButton);
    expect(state.stopStream).toHaveBeenCalled();
  });

  it('shows the conversation id badge once a session exists', () => {
    mockedUseDifyStream.mockReturnValue(
      hookState({ conversationId: 'abcdef1234567890' })
    );
    render(<ChatInterface />);

    expect(screen.getByText(/Session: abcdef12/)).toBeInTheDocument();
  });

  it('shows a global error banner', () => {
    mockedUseDifyStream.mockReturnValue(hookState({ error: 'Backend unreachable' }));
    render(<ChatInterface />);

    expect(screen.getByText(/Backend unreachable/)).toBeInTheDocument();
  });

  it('clears the conversation via the Clear button', async () => {
    const state = hookState();
    mockedUseDifyStream.mockReturnValue(state);
    render(<ChatInterface />);

    await userEvent.click(screen.getByRole('button', { name: 'Clear' }));
    expect(state.clearMessages).toHaveBeenCalled();
  });
});
