export const TAB_MIN_WIDTH = 132;
export const TAB_MAX_WIDTH = 220;
export const TAB_GAP = 4;
export const MORE_TABS_WIDTH = 104;

export function allTabsFit(count: number, width: number): boolean {
  return count <= 1 || count * TAB_MIN_WIDTH + (count - 1) * TAB_GAP <= width;
}

function visibleTabCount(count: number, width: number): number {
  if (allTabsFit(count, width)) return count;
  return Math.max(1, Math.floor((width - MORE_TABS_WIDTH) / (TAB_MIN_WIDTH + TAB_GAP)));
}

export function splitTabs<T extends { id: string }>(tabs: readonly T[], activeId: string, width: number) {
  const count = visibleTabCount(tabs.length, width);
  const visible: T[] = [];
  const hidden: T[] = [];
  const activeIndex = tabs.findIndex((tab) => tab.id === activeId);
  const leadingCount = activeIndex >= count ? count - 1 : count;
  for (const [index, tab] of tabs.entries()) {
    (index < leadingCount || index === activeIndex ? visible : hidden).push(tab);
  }
  return { visible, hidden };
}

export function macTabEdges(count: number, width: number, left: number) {
  const step = TAB_MIN_WIDTH + TAB_GAP;
  const tolerance = 0.5;
  const leftCount = Math.max(0, Math.min(count, Math.ceil((left - tolerance) / step)));
  const rightStart = Math.max(0, Math.min(count, Math.floor((left + width - TAB_MIN_WIDTH + tolerance) / step) + 1));
  return { leftCount, rightStart };
}

export function verticalTabWheelDelta(
  event: Pick<WheelEvent, 'deltaX' | 'deltaY' | 'deltaMode' | 'ctrlKey'>,
  width: number
) {
  if (event.ctrlKey || Math.abs(event.deltaY) <= Math.abs(event.deltaX)) return 0;
  const multiplier = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? width : 1;
  return event.deltaY * multiplier;
}
