import { Component, type ReactNode } from 'react';

export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    if (!this.state.failed) return this.props.children;
    return <main className="binding-page">
      <section className="panel" role="alert">
        <h1>页面暂时无法显示</h1>
        <p>请重新加载后再试。尚未提交的内容可能需要重新填写。</p>
        <button type="button" onClick={() => location.reload()}>重新加载</button>
      </section>
    </main>;
  }
}
