import type {Incident} from './types';
/** First successful snapshot is the baseline; only later IDs generate notifications. */
export function newlyReceived(previous:ReadonlySet<string>|null,items:Incident[]):Incident[]{
 return previous===null?[]:items.filter(incident=>!previous.has(incident.incidentId));
}
export const incidentTarget=(incident:Pick<Incident,'lotId'|'equipmentId'>)=>
 `/dashboard?${new URLSearchParams({lot:incident.lotId,pm:incident.equipmentId})}`;
