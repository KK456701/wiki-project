import assert from 'node:assert/strict';
import test from 'node:test';

import {
  detailFieldLabel,
  formatDetailCell,
  visibleDetailKeys,
} from '../src/components/details/detail-fields.ts';

test('detail field labels use Chinese names for known business columns', () => {
  assert.equal(detailFieldLabel('PERSON_NAME'), '患者姓名');
  assert.equal(detailFieldLabel('encounter_id'), '就诊标识');
  assert.equal(detailFieldLabel('ADMITTED_TO_WARD_AT'), '入区时间');
  assert.equal(detailFieldLabel('TRANSFER_WITHIN_TWO_DAY'), '48小时内转科判定');
  assert.equal(detailFieldLabel('CURRENT_ATTENDER_NAME'), '当前主治医师姓名');
  assert.equal(detailFieldLabel('MRAS_TARGET_DEFINITION_ID'), '目标定义标识');
  assert.equal(detailFieldLabel('EVENT_AT'), '事件时间');
  assert.equal(detailFieldLabel('PERSONNAME'), '患者姓名');
  assert.equal(detailFieldLabel('currentDeptName'), '当前科室名称');
  assert.equal(detailFieldLabel('MEMO'), '备注');
});

test('detail field helpers hide protocol columns and format business values', () => {
  assert.deepEqual(
    visibleDetailKeys({ __detail_id: 'D1', PERSON_NAME: '张三', __meets_numerator: 1 }),
    ['PERSON_NAME'],
  );
  assert.equal(formatDetailCell('__is_median_sample', 1), '是');
  assert.equal(formatDetailCell('PERSON_NAME', null), '—');
});
