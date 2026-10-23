import {useEffect,useState} from 'react';
/** 입력이 delay(ms) 동안 멈추면 값을 반영한다. flush는 기다리지 않고 바로 반영한다(Enter 등) */
export function useDebouncedValue<T>(value:T,delay=300){
 const [debounced,setDebounced]=useState(value);
 useEffect(()=>{
  const timer=window.setTimeout(()=>setDebounced(value),delay);
  return ()=>window.clearTimeout(timer);
 },[value,delay]);
 return {debounced,flush:()=>setDebounced(value)};
}
