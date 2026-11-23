import io.casehub.neocortex.caps.*;
import io.casehub.neocortex.caps.testing.InMemoryCapsEngine;
import io.casehub.neocortex.cognitive.index.DispositionAxes;
import java.util.*;
public class CapsRepro {
  static String top(SettlingResult r,int n){var l=new ArrayList<>(r.attractors());l.sort((a,b)->Double.compare(b.strength(),a.strength()));var sb=new StringBuilder();for(int i=0;i<Math.min(n,l.size());i++)sb.append(l.get(i).nodeId()).append(String.format("=%.3f ",l.get(i).strength()));return sb.toString();}
  public static void main(String[] a){
    var e=new InMemoryCapsEngine();
    var def=e.initializeAgent("t","def",new DispositionAxes("cooperative","moderate","calculated","moderate","cooperative"));
    var empty=e.settle(def,Map.of());
    System.out.println("EMPTY input: attractors="+empty.attractors().size()+" conv="+empty.convergence()+" iters="+empty.iterations()+" top: "+top(empty,8));
    String[][] cases={{"physical_threat","flight"},{"betrayal","trust"},{"reward","approach"},{"rejection","withdraw"}};
    for(var c:cases){var r=e.settle(def,Map.of(c[0],1.0));
      System.out.printf("%s=1.0: attractors=%d  %s: %.3f -> %.3f   top: %s%n",c[0],r.attractors().size(),c[1],empty.activations().getOrDefault(c[1],Double.NaN),r.activations().getOrDefault(c[1],Double.NaN),top(r,5));}
    var warm=e.settle(def,Map.of("secure_attachment",1.0,"acceptance",1.0,"perceived_responsiveness",1.0));
    System.out.printf("WARM: trust=%.3f withdraw=%.3f flight=%.3f%n",warm.activations().get("trust"),warm.activations().get("withdraw"),warm.activations().get("flight"));
    // set overlap between threat and reward
    var th=e.settle(def,Map.of("physical_threat",1.0,"social_threat",1.0)); var rw=e.settle(def,Map.of("reward",1.0,"acceptance",1.0));
    var s1=new TreeSet<String>();th.attractors().forEach(x->s1.add(x.nodeId()));var s2=new TreeSet<String>();rw.attractors().forEach(x->s2.add(x.nodeId()));var inter=new TreeSet<>(s1);inter.retainAll(s2);
    System.out.println("threat attractors="+s1.size()+" reward attractors="+s2.size()+" shared="+inter.size());
    // opposite personalities
    var bold=e.initializeAgent("t","bold",new DispositionAxes("competitive","flexible","bold","high","competitive"));
    var caut=e.initializeAgent("t","caut",new DispositionAxes("cooperative","strict","conservative","low","avoidant"));
    var rb=e.settle(bold,Map.of("social_threat",1.0)); var rc=e.settle(caut,Map.of("social_threat",1.0));
    double maxd=0;String arg="";for(var k:rb.activations().keySet()){double d=Math.abs(rb.activations().get(k)-rc.activations().getOrDefault(k,0.0));if(d>maxd){maxd=d;arg=k;}}
    System.out.printf("bold vs cautious under social_threat: max settled diff=%.3f at %s%n",maxd,arg);
    // fear learning
    var st=def; var in=Map.of("social_threat",1.0,"physical_threat",1.0);
    double bis0=e.settle(st,in).activations().get("BIS_activation");
    String key=null;for(var k:st.weights().keySet()) if(k.contains("social_threat")&&k.contains("BIS_activation")) key=k;
    double w0=st.weights().get(key).excitatory();
    for(int i=0;i<30;i++) st=e.updateWeights(st,new WeightUpdateContext(in,0.8,-1.0,1.0,"continuous",1.0));
    System.out.printf("30 fear events: %s weight %.3f -> %.3f ; BIS under threat %.3f -> %.3f%n",key,w0,st.weights().get(key).excitatory(),bis0,e.settle(st,in).activations().get("BIS_activation"));
  }
}
