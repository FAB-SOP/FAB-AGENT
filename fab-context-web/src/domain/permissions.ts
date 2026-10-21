import {type Session} from '../api/types';
/** 서버가 내려준 사건 운영 범위. 구버전 응답은 쓰기 불가로 취급한다. */
export const canManageInvestigation=(session:Session|null|undefined,process:string)=>!!session?.investigationProcessScopes?.includes(process);
