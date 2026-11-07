import { createRoot } from 'react-dom/client';
import { Site } from './App';
import { ErrorBoundary } from './ErrorBoundary';
import './styles.css';
import './AdminConsole.css';
import './Download.css';

createRoot(document.getElementById('root')!).render(<ErrorBoundary><Site /></ErrorBoundary>);
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.getRegistration('/').then(registration => {
      const script = registration?.active?.scriptURL;
      if (script && new URL(script).origin === location.origin && new URL(script).pathname === '/sw.js')
        return registration?.unregister();
    }).catch(() => { console.warn('旧版离线服务清理未完成，请联网刷新管理台。'); });
  });
}
