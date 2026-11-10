import { useCallback, useLayoutEffect, useRef, useState } from 'react';
import { macTabEdges, verticalTabWheelDelta } from './tab-strip';

export function useMacTabScroll(enabled: boolean, activeId: string | undefined, count: number, stripWidth: number) {
  const viewport = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ left: 0, width: 0 });
  const measure = useCallback(() => {
    const strip = viewport.current;
    if (!strip) return;
    const left = strip.scrollLeft;
    const width = strip.clientWidth;
    setPosition((previous) => (previous.left === left && previous.width === width ? previous : { left, width }));
  }, []);

  useLayoutEffect(() => {
    const strip = viewport.current;
    if (!enabled || !strip) return;
    const wheel = (event: WheelEvent) => {
      const delta = verticalTabWheelDelta(event, strip.clientWidth);
      if (!delta || strip.scrollWidth <= strip.clientWidth) return;
      event.preventDefault();
      strip.scrollLeft += delta;
    };
    strip.addEventListener('wheel', wheel, { passive: false });
    strip.addEventListener('scroll', measure, { passive: true });
    const observer = new ResizeObserver(measure);
    observer.observe(strip);
    return () => {
      strip.removeEventListener('wheel', wheel);
      strip.removeEventListener('scroll', measure);
      observer.disconnect();
    };
  }, [enabled, measure]);

  useLayoutEffect(() => {
    const strip = viewport.current;
    if (!enabled || !strip) return;
    const active = strip.querySelector('.tab.active');
    if (active) {
      const bounds = strip.getBoundingClientRect();
      const item = active.getBoundingClientRect();
      if (item.left < bounds.left) strip.scrollLeft += item.left - bounds.left;
      else if (item.right > bounds.right) strip.scrollLeft += item.right - bounds.right;
    }
    measure();
  }, [enabled, activeId, count, stripWidth, measure]);

  return { viewport, ...macTabEdges(count, position.width, position.left) };
}
