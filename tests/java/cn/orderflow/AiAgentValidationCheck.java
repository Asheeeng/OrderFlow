package cn.orderflow;
import java.lang.reflect.*;
import java.util.*;
public class AiAgentValidationCheck {
 public static void main(String[] args)throws Exception{
  var c=AiAgent.class.getDeclaredConstructor(AiProvider.class,AiStore.class,AiTools.class);c.setAccessible(true);
  var agent=c.newInstance(null,null,null);
  var f=AiAgent.class.getDeclaredMethod("verification",String.class,List.class);f.setAccessible(true);
  var row=Map.<String,Object>of("sales",36526538.65,"paid_orders",17959);
  var r=new AiTools.Result("query_sales",Map.of("start","2026-10-29","endExclusive","2026-11-28"),"replica","sales","SELECT",1,List.of(row),"","","");
  String[] answers={"最近 30 天销售额 36,526,538.65 元，约 3,652.65 万元，17,959 笔。",
   "最近30天销售36,526,538.65 元，成功17,959。",
   "最近 30 天销售额 36,526,538.65 元。"};
  for(var answer:answers)System.out.println(answer+" => "+f.invoke(agent,answer,List.of(r)));
 }
}
