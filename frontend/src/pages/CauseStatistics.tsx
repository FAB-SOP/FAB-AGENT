import {useEffect,useRef,useState} from 'react';
import {Link} from 'react-router-dom';
import {useInfiniteQuery,useQuery} from '@tanstack/react-query';
import * as echarts from 'echarts/core';
import {BarChart,PieChart} from 'echarts/charts';
import {GridComponent,TooltipComponent} from 'echarts/components';
import {CanvasRenderer} from 'echarts/renderers';
import {RefreshCw} from 'lucide-react';
import {api} from '../api/client';
import {sensorNames,when} from '../api/types';
import {
 CAUSE_CATEGORIES,causeColors,causeLabels,selectionLabel,statisticsQuery,toggle,
 type CauseReportPage,type CauseStatistics as Stats,
} from '../api/causeStatistics';
import {useAuth} from '../auth';
import {Empty,ErrorMessage,PageHeading,Panel} from '../components/ui';
echarts.use([PieChart,BarChart,GridComponent,TooltipComponent,CanvasRenderer]);

/** echarts를 붙이고 클릭을 넘긴다 — option이 바뀌면 다시 그린다 */
function useChart(option:echarts.EChartsCoreOption,onClick:(params:any)=>void){
 const node=useRef<HTMLDivElement>(null);
 const click=useRef(onClick);click.current=onClick;
 const chart=useRef<echarts.ECharts|null>(null);
 useEffect(()=>{
  if(!node.current)return;
  chart.current=echarts.init(node.current);
  chart.current.on('click',params=>click.current(params));
  const observer=new ResizeObserver(()=>chart.current?.resize());
  observer.observe(node.current);
  return()=>{observer.disconnect();chart.current?.dispose();chart.current=null;};
 },[]);
 useEffect(()=>{chart.current?.setOption(option,true);},[option]);
 return node;
}
function CausePie({stats,selected,onToggle}:{stats:Stats;selected:string[];onToggle:(category:string)=>void}){
 const counts=new Map(stats.categoryCounts.map(item=>[item.category,item.count]));
 const option={
  tooltip:{trigger:'item',formatter:'{b}: {c}건 ({d}%)'},
  series:[{type:'pie',radius:['45%','72%'],selectedMode:false,label:{formatter:'{b}\n{c}건'},
   data:CAUSE_CATEGORIES.map(category=>({
    name:causeLabels[category],value:counts.get(category)||0,category,
    itemStyle:{color:causeColors[category],opacity:!selected.length||selected.includes(category)?1:0.3},
   })).filter(item=>item.value>0),
  }],
 };
 const node=useChart(option,params=>params.data?.category&&onToggle(params.data.category));
 return <div ref={node} className="cause-pie" role="img" aria-label="원인 4M 분포. 조각을 눌러 여러 개 선택"/>;
}
function MonthlyBars({stats,selected,onToggle}:{stats:Stats;selected:number[];onToggle:(month:number)=>void}){
 const option={
  tooltip:{trigger:'axis',formatter:(items:any)=>`${items[0].name}: ${items[0].value}건`},
  grid:{left:32,right:12,top:12,bottom:28},
  xAxis:{type:'category',data:stats.monthly.map(item=>item.month+'월'),axisTick:{show:false}},
  yAxis:{type:'value',minInterval:1},
  series:[{type:'bar',barMaxWidth:22,data:stats.monthly.map(item=>({
   value:item.count,itemStyle:{color:!selected.length||selected.includes(item.month)?'#197a6d':'#b9d3cf',borderRadius:[4,4,0,0]},
  }))}],
 };
 const node=useChart(option,params=>onToggle(params.dataIndex+1));
 return <div ref={node} className="cause-bars" role="img" aria-label="월별 확정 보고서 건수. 막대를 눌러 여러 달 선택"/>;
}
function ReportList({year,months,categories}:{year:number;months:number[];categories:string[]}){
 const reports=useInfiniteQuery({
  queryKey:['cause-statistics-reports',year,months,categories],
  queryFn:({pageParam})=>api<CauseReportPage>('/api/cause-statistics/reports'+statisticsQuery({year,months,categories,page:pageParam,size:20})),
  initialPageParam:0,
  getNextPageParam:(last:CauseReportPage)=>last.page+1<last.totalPages?last.page+1:undefined,
 });
 const items=reports.data?.pages.flatMap(page=>page.items)||[];
 const total=reports.data?.pages[0]?.totalElements??0;
 return <Panel title={`확정 보고서 ${total}건`} className="cause-reports">
  {reports.error&&<ErrorMessage error={reports.error}/>}
  {!items.length&&!reports.isPending&&<Empty>이 조건에 맞는 확정 보고서가 없습니다.</Empty>}
  {items.map(report=><Link key={report.reportId} className="cause-report-row" to={'/reports/'+report.reportId}>
   <div>
    <strong>{report.incidentDisplayId} · {report.title}</strong>
    <span className="small muted">발생 {when(report.detectedAt)} · 확정 {report.confirmedAt?when(report.confirmedAt):'—'} · {report.equipmentId}</span>
    {!!report.classification?.equipmentParameters?.length&&<span className="small muted">
     관련 파라미터 · {report.classification.equipmentParameters.map(id=>sensorNames[id]||id).join(', ')}
    </span>}
   </div>
   {report.classification
    ?<span className="cause-tag" style={{borderColor:causeColors[report.classification.category],color:causeColors[report.classification.category]}}>
     {causeLabels[report.classification.category]||report.classification.category}
    </span>
    :<span className="cause-tag muted">미분류</span>}
  </Link>)}
  {reports.hasNextPage&&<button className="text-button" disabled={reports.isFetchingNextPage} onClick={()=>reports.fetchNextPage()}>
   {reports.isFetchingNextPage?'불러오는 중…':'더 보기'}
  </button>}
 </Panel>;
}
/** 원인 통계 — 이상 발생일 기준, 확정 보고서만. 관리자만 (서버도 검사) */
export function CauseStatistics(){
 const {session}=useAuth();
 const [year,setYear]=useState<number|undefined>();
 const [months,setMonths]=useState<number[]>([]);
 const [categories,setCategories]=useState<string[]>([]);
 const stats=useQuery({
  queryKey:['cause-statistics',year,months],
  queryFn:()=>api<Stats>('/api/cause-statistics'+statisticsQuery({year,months})),
  enabled:session?.role==='MANAGER',
 });
 if(session?.role!=='MANAGER')return <Empty>원인 통계는 관리자만 볼 수 있습니다.</Empty>;
 const data=stats.data;
 const shownYear=data?.year||year||new Date().getFullYear();
 const reset=()=>{setMonths([]);setCategories([]);};
 return <>
  <PageHeading kicker="CAUSE STATISTICS" title="원인 통계" description="확정 보고서의 원인 분류를 이상 발생일 기준으로 봅니다."
   extra={<div className="cause-toolbar">
    <select aria-label="연도" value={shownYear} onChange={event=>{setYear(Number(event.target.value));reset();}}>
     {(data?.availableYears||[shownYear]).map(option=><option key={option} value={option}>{option}년</option>)}
    </select>
    <button className="button secondary" onClick={reset}><RefreshCw size={16}/>초기화</button>
   </div>}/>
  <p className="cause-selection">선택: <strong>{selectionLabel(shownYear,months,categories)}</strong></p>
  {stats.error&&<ErrorMessage error={stats.error}/>}
  {data&&<div className="cause-grid">
   <Panel title="원인 분류" className="cause-charts">
    <p className="small muted">조각과 막대를 눌러 여러 개 고를 수 있습니다. 분류 {data.classifiedCount}건{data.unclassifiedCount>0&&` · 미분류 ${data.unclassifiedCount}건(차트 밖)`}</p>
    {data.classifiedCount?<CausePie stats={data} selected={categories} onToggle={category=>setCategories(list=>toggle(list,category))}/>
     :<Empty>선택한 기간에 분류된 확정 보고서가 없습니다.</Empty>}
    <div className="cause-legend">{CAUSE_CATEGORIES.map(category=><button key={category} type="button"
     className={'cause-legend-item'+(categories.includes(category)?' active':'')} onClick={()=>setCategories(list=>toggle(list,category))}>
     <i style={{background:causeColors[category]}}/>{causeLabels[category]} {data.categoryCounts.find(item=>item.category===category)?.count||0}
    </button>)}</div>
    <h3 className="cause-sub">월별 건수 <span className="small muted">연간 {data.annualTotalCount}건</span></h3>
    <MonthlyBars stats={data} selected={months} onToggle={month=>setMonths(list=>toggle(list,month))}/>
   </Panel>
   <ReportList year={shownYear} months={months} categories={categories}/>
  </div>}
 </>;
}
