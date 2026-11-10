export const TAB_MIN_WIDTH = 132;
export const TAB_MAX_WIDTH = 220;
export const TAB_GAP = 4;
export const MORE_TABS_WIDTH = 104;

function visibleTabCount(count: number, width: number): number {
  if (count <= 1 || count * TAB_MIN_WIDTH + (count - 1) * TAB_GAP <= width) return count;
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
