import '@testing-library/jest-dom/vitest';
import { vi } from 'vitest';

// jsdom does not implement scrollIntoView (used for auto-scrolling the chat)
Element.prototype.scrollIntoView = vi.fn();
