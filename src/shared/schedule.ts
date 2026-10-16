import type { Plan, DoseLog, Todo } from './types';
export function localClock(now: Date, timezone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(now);
  const p = Object.fromEntries(parts.map(p => [p.type, p.value]));
  return { date: `${p.year}-${p.month}-${p.day}`, time: `${p.hour}:${p.minute}` };
}
export const minutes = (time: string) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5));
export const dayNumber = (date: string) => Math.floor(Date.parse(`${date}T00:00:00Z`) / 86400000);
export function scheduledOn(plan: Plan, date: string) {
  if (!plan.enabled || date < plan.start_date || (plan.end_date && date > plan.end_date) || plan.frequency === 'as_needed') return false;
  if (plan.frequency === 'weekly') return (JSON.parse(plan.weekdays) as number[]).includes(new Date(`${date}T00:00:00Z`).getUTCDay());
  if (plan.frequency === 'interval') return (dayNumber(date) - dayNumber(plan.start_date)) % plan.interval_days === 0;
  return true;
}
export function todayTodos(plans: Plan[], logs: DoseLog[], timezone: string, now = new Date()): Todo[] {
  const clock = localClock(now, timezone);
  const items: Todo[] = [];
  for (const plan of plans) {
    const recorded = logs.filter(l => l.plan_id === plan.id && l.local_date === clock.date && l.status !== 'undone');
    const times = new Set<string>(scheduledOn(plan, clock.date) ? JSON.parse(plan.times) as string[] : []);
    for (const log of recorded) times.add(log.scheduled_time);
    for (const time of times) {
      const log = recorded.find(l => l.scheduled_time === time);
      items.push({
        key: `${plan.id}:${clock.date}:${time}`, plan, local_date: clock.date, scheduled_time: time,
        state: log ? log.status === 'taken' ? 'taken' : 'skipped' : minutes(clock.time) > minutes(time) + 60 ? 'overdue' : 'pending',
        log,
      });
    }
  }
  return items.sort((a, b) => a.scheduled_time.localeCompare(b.scheduled_time) || a.plan.medicine_name.localeCompare(b.plan.medicine_name));
}
