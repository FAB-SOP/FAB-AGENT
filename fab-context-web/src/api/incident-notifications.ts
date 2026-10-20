import type {Incident} from './types';
/** First successful snapshot is the baseline; only later IDs generate notifications. */
export function newlyReceived(previous:ReadonlySet<string>|null,items:Incident[]):Incident[]{return previous===null?[]:items.filter(i=>!previous.has(i.incidentId));}
export const incidentTarget=(i:Pick<Incident,'lotId'|'equipmentId'>)=>`/dashboard?${new URLSearchParams({lot:i.lotId,pm:i.equipmentId})}`;
