import {
  startOfMonth,
  endOfMonth,
  subMonths,
  startOfQuarter,
  endOfQuarter,
  subQuarters,
  startOfYear,
  endOfYear,
  subYears,
} from 'date-fns';
import type { Ref } from 'vue';

export const DATE_SHORTCUTS = [
  { label: '本月', key: 'thisMonth' },
  { label: '上月', key: 'lastMonth' },
  { label: '本季度', key: 'thisQuarter' },
  { label: '上季度', key: 'lastQuarter' },
  { label: '今年', key: 'thisYear' },
  { label: '去年', key: 'lastYear' },
] as const;

export type DateShortcutKey = (typeof DATE_SHORTCUTS)[number]['key'];

export function useDateShortcuts(dateRangeRef: Ref<Date[]>) {
  function setDateRange(key: DateShortcutKey) {
    const now = new Date();
    switch (key) {
      case 'thisMonth': {
        dateRangeRef.value = [startOfMonth(now), endOfMonth(now)];
        break;
      }
      case 'lastMonth': {
        const last = subMonths(now, 1);
        dateRangeRef.value = [startOfMonth(last), endOfMonth(last)];
        break;
      }
      case 'thisQuarter': {
        dateRangeRef.value = [startOfQuarter(now), endOfQuarter(now)];
        break;
      }
      case 'lastQuarter': {
        const last = subQuarters(now, 1);
        dateRangeRef.value = [startOfQuarter(last), endOfQuarter(last)];
        break;
      }
      case 'thisYear': {
        dateRangeRef.value = [startOfYear(now), endOfYear(now)];
        break;
      }
      case 'lastYear': {
        const last = subYears(now, 1);
        dateRangeRef.value = [startOfYear(last), endOfYear(last)];
        break;
      }
    }
  }

  return { setDateRange };
}
