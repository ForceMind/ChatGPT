const params = new URL(location.href).searchParams;
const deadline = Number(params.get('deadline'));
if (params.get('platform') === 'win32') {
  document.getElementById('idle-detail')!.textContent =
    '应用已空闲，所有标签未检测到任务。安装后，下次打开将使用新版。回到应用或取消会延后。';
}
const label = document.getElementById('seconds')!;
const paint = () => {
  label.textContent = String(Math.max(0, Math.ceil((deadline - Date.now()) / 1000)));
};
paint();
setInterval(paint, 1000);
document.getElementById('cancel')!.addEventListener('click', () => {
  (window as typeof window & { updateNotice: { cancel: () => void } }).updateNotice.cancel();
});
