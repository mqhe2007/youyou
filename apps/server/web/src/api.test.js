import assert from 'node:assert/strict';
import test from 'node:test';
import { getApiErrorMessage } from './api.js';

test('conflicts show the reason returned by the server', () => {
  for (const message of [
    '服务端删除能力未启用：回收站目录不可用',
    '移动原件到回收站失败：权限不足；本次未删除任何原件',
  ]) {
    const error = Object.assign(new Error(message), { code: 'conflict' });
    assert.equal(getApiErrorMessage(error), message);
  }
});

test('conflicts without a reason provide a neutral fallback', () => {
  for (const message of ['', '   ']) {
    const error = Object.assign(new Error(message), { code: 'conflict' });
    assert.equal(getApiErrorMessage(error), '当前操作与现有状态冲突，请刷新后重试。');
  }
});

test('nonempty folders report the directory restriction', () => {
  const error = Object.assign(new Error('directory is not empty: library/uploads/unknown'), {
    code: 'directory_not_empty',
  });
  assert.equal(getApiErrorMessage(error), '文件夹非空，无法删除。');
});

test('internal errors retain their safe message and network failures stay distinct', () => {
  const error = Object.assign(new Error('internal detail'), { code: 'internal_error' });
  assert.equal(getApiErrorMessage(error), '服务端暂时无法完成操作，请稍后再试。');
  assert.equal(getApiErrorMessage(new TypeError('Failed to fetch')), '无法连接服务端，请确认服务正在运行。');
});
