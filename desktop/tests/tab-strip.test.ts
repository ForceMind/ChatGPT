import test from 'node:test';
import assert from 'node:assert/strict';
import { splitTabs } from '../src/renderer/tab-strip';

const tabs = ['a', 'b', 'c', 'd', 'e'].map((id) => ({ id }));

test('the strip uses the remaining space before reserving a menu button', () => {
  assert.deepEqual(splitTabs(tabs.slice(0, 3), 'c', 404), { visible: tabs.slice(0, 3), hidden: [] });
  assert.deepEqual(splitTabs(tabs, 'b', 512), { visible: tabs.slice(0, 3), hidden: tabs.slice(3) });
});

test('selecting a hidden tab puts it in the visible strip without reordering the source tabs', () => {
  const { visible, hidden } = splitTabs(tabs, 'e', 512);
  assert.deepEqual(
    visible.map((tab) => tab.id),
    ['a', 'b', 'e']
  );
  assert.deepEqual(
    hidden.map((tab) => tab.id),
    ['c', 'd']
  );
  assert.equal(visible[2], tabs[4]);
  assert.deepEqual(
    tabs.map((tab) => tab.id),
    ['a', 'b', 'c', 'd', 'e']
  );
});

test('the current tab stays visible at minimum width and before the initial measurement', () => {
  for (const width of [0, 239, 240]) {
    assert.deepEqual(splitTabs(tabs, 'c', width), { visible: [tabs[2]], hidden: [tabs[0], tabs[1], tabs[3], tabs[4]] });
  }
  assert.deepEqual(splitTabs([tabs[0]], 'a', 100), { visible: [tabs[0]], hidden: [] });
});
