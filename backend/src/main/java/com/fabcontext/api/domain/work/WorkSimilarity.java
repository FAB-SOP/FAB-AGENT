package com.fabcontext.api.domain.work;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 보수적인 문구 중복 검사. 업무 종류·대상은 호출부에서 먼저 비교한다. */
public final class WorkSimilarity {
 private WorkSimilarity() {}
 private static String normalize(String value) {
  return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
   .replaceAll("[\\p{P}\\p{Z}\\s]", "");
 }
 public static boolean same(String a,String b) {
  String left=normalize(a),right=normalize(b);
  if(left.isEmpty()||right.isEmpty()) return false;
  if(left.equals(right)) return true;
  // 숫자·설비·센서가 달라진 업무는 문구가 비슷해도 묶지 않는다.
  if(!tokens(left).equals(tokens(right))) return false;
  if(Math.min(left.length(),right.length())<12) return false;
  Set<String> x=grams(left),y=grams(right),intersection=new HashSet<>(x);
  intersection.retainAll(y);
  return 2.0*intersection.size()/(x.size()+y.size())>=0.88;
 }
 private static Set<String> tokens(String s) {
  Set<String> out=new HashSet<>();
  var matcher=java.util.regex.Pattern.compile("[a-z_]+|[0-9]+(?:[.][0-9]+)?").matcher(s);
  while(matcher.find()) out.add(matcher.group());
  for(String sensor:new String[]{"유량","압력","온도","회전","전류","두께","펌프","필터","배관","패드"}) if(s.contains(sensor)) out.add(sensor);
  return out;
 }
 private static Set<String> grams(String s) {
  Set<String> out=new HashSet<>();for(int i=0;i<s.length()-2;i++)out.add(s.substring(i,i+3));return out;
 }
}
