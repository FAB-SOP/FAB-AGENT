import type {AnalysisPriority} from '../api/types';
export interface PriorityView {tone:string;label:string;bars:number;reason:string}
const levels:Record<string,PriorityView>={
 HIGH:{tone:'high',label:'상',bars:3,reason:''},
 MEDIUM:{tone:'medium',label:'중',bars:2,reason:''},
 LOW:{tone:'low',label:'하',bars:1,reason:''},
};
/** 서버가 내려준 값만 표시한다. 판정 전·실패·알 수 없는 값을 '하'로 바꾸지 않는다. */
export function priorityView(priority?:AnalysisPriority|null):PriorityView{
 const reason=priority?.reason||'';
 if(priority?.status==='COMPLETED'&&priority.level&&levels[priority.level])return {...levels[priority.level],reason};
 if(priority?.status==='PENDING')return {tone:'pending',label:'분석 중',bars:0,reason:reason||'기본 분석이 끝나면 우선순위가 표시됩니다.'};
 if(priority?.status==='FAILED')return {tone:'failed',label:'판정 실패',bars:0,reason:reason||'우선순위를 판정하지 못했습니다.'};
 return {tone:'none',label:'미평가',bars:0,reason:reason||'아직 우선순위를 평가하지 않은 사건입니다.'};
}
