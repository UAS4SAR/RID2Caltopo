#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <functional>
#include <queue>
#include <set>
#include <stdexcept>
#include <vector>
namespace r2c::mapping {
// One bounded camera-pair graph at a time; no image blending or invented pixels.
class SeamCut {
 struct Edge {int to,reverse;double capacity;};
 std::vector<std::vector<Edge>> edges;
 std::vector<int> level,next;
 std::function<void()> check;
 double send(int p,int sink,double flow,int depth=0){
  if(depth>1024)throw std::length_error("Seam path exceeds bounded stack depth.");
  if(p==sink)return flow;
  for(int& i=next[p];i<int(edges[p].size());++i){auto& e=edges[p][i];if(e.capacity>1e-9&&level[e.to]==level[p]+1){double used=send(e.to,sink,std::min(flow,e.capacity),depth+1);if(used>1e-9){e.capacity-=used;edges[e.to][e.reverse].capacity+=used;return used;}}}return 0;
 }
 public:
 SeamCut(int n,std::function<void()> check):edges(n),level(n),next(n),check(check){}
 void add(int a,int b,double forward,double backward=0){int ai=edges[a].size(),bi=edges[b].size();edges[a].push_back({b,bi,forward});edges[b].push_back({a,ai,backward});}
 std::vector<bool> solve(int source,int sink){
  for(;;){check();std::fill(level.begin(),level.end(),-1);std::queue<int> q;q.push(source);level[source]=0;int visits=0;
   while(!q.empty()){if(++visits%4096==0)check();int p=q.front();q.pop();for(auto& e:edges[p])if(e.capacity>1e-9&&level[e.to]<0){level[e.to]=level[p]+1;q.push(e.to);}}
   if(level[sink]<0)break;std::fill(next.begin(),next.end(),0);while(send(source,sink,1e30)>1e-9)check();
  }
  std::vector<bool> reached(edges.size());std::queue<int> q;q.push(source);reached[source]=true;
  while(!q.empty()){int p=q.front();q.pop();for(auto& e:edges[p])if(e.capacity>1e-9&&!reached[e.to]){reached[e.to]=true;q.push(e.to);}}return reached;
 }
};
// Scores use viewing-ray angle in radians, with small image-border penalties.
// Seam continuity may trade at most two degrees against the best valid view.
inline constexpr double overheadSeamAllowance = 2.0 * 3.14159265358979323846 / 180.0;
inline double overheadProjectionScore(double dx,double dy,double dz,double radiusSquared,double edge){
 return std::atan2(std::hypot(dx,dy),std::max(3.,std::abs(dz)))
     + .005*radiusSquared + (edge<32?(32-edge)*.02:0);
}
struct SeamSample {double score=INFINITY;std::array<double,3> rgb{};};
inline void choosePhotoSeams(std::vector<int>& labels,int width,int height,
 const std::function<SeamSample(int,int)>& sample,const std::function<void(double)>& progress){
 // Anchor eligibility to the initial best view, not the preceding pair cut:
 // successive pair swaps must not walk away from the overhead candidate.
 std::vector<double> bestScores(labels.size(),INFINITY);
 for(size_t p=0;p<labels.size();++p){if(p%4096==0)progress(0);if(labels[p]>=0)bestScores[p]=sample(p,labels[p]).score;}
 std::set<std::pair<int,int>> pairs;
 for(int y=0;y<height;++y)for(int x=0;x<width;++x){int p=y*width+x,a=labels[p];if(a<0)continue;for(int q:{x+1<width?p+1:-1,y+1<height?p+width:-1})if(q>=0&&labels[q]>=0&&labels[q]!=a)pairs.emplace(std::min(a,labels[q]),std::max(a,labels[q]));}
 std::vector<int> nodes(labels.size(),-1);int pairIndex=0;
 for(auto [a,b]:pairs){auto check=[&]{progress(double(pairIndex)/std::max(size_t(1),pairs.size()));};check();
  std::vector<int> active;std::vector<double> ca,cb,disagreement;
  for(size_t p=0;p<labels.size();++p){if(p%4096==0)check();if(labels[p]!=a&&labels[p]!=b)continue;
   auto sa=sample(p,a),sb=sample(p,b);if(!std::isfinite(sa.score)||!std::isfinite(sb.score)||sa.score>bestScores[p]+overheadSeamAllowance||sb.score>bestScores[p]+overheadSeamAllowance)continue;
   nodes[p]=active.size();active.push_back(p);double base=std::min(sa.score,sb.score);ca.push_back((sa.score-base)*.2);cb.push_back((sb.score-base)*.2);
   double delta=0;for(int k=0;k<3;++k)delta+=std::abs(sa.rgb[k]-sb.rgb[k])/765.;disagreement.push_back(.02+delta);
   if(active.size()>200000)break;
  }
  ++pairIndex;
  if(active.empty())continue;
  // Oversized overlaps retain the prior nearest-view selection rather than
  // allocating an unbounded graph on a field tablet.
  if(active.size()>200000){for(int p:active)nodes[p]=-1;continue;}
  int source=active.size(),sink=source+1;SeamCut cut(sink+1,check);
  for(int i=0;i<int(active.size());++i){if(i%4096==0)check();int p=active[i],x=p%width,y=p/width;double costA=ca[i],costB=cb[i];
   for(int q:{x>0?p-1:-1,x+1<width?p+1:-1,y>0?p-width:-1,y+1<height?p+width:-1}){if(q<0)continue;int j=nodes[q];
    if(j>=0){if(j>i){double w=disagreement[i]+disagreement[j];cut.add(i,j,w,w);}}
    else if(labels[q]==a)costB+=2*disagreement[i];else if(labels[q]==b)costA+=2*disagreement[i];
    // Changing an A/B edge also changes its boundary with a third image.
    // Omitting this term can move a seam onto an otherwise continuous feature.
    else if(labels[q]>=0){
     auto sc=sample(p,labels[q]);
     if(std::isfinite(sc.score)){auto sa=sample(p,a),sb=sample(p,b);double da=0,db=0;
      for(int k=0;k<int(sc.rgb.size());++k){da=std::max(da,std::abs(sa.rgb[k]-sc.rgb[k])/255.);db=std::max(db,std::abs(sb.rgb[k]-sc.rgb[k])/255.);}costA+=2*da;costB+=2*db;}
    }
   }
   cut.add(source,i,costB);cut.add(i,sink,costA);
  }
  std::vector<bool> result;
  try{result=cut.solve(source,sink);}catch(const std::length_error&){for(int p:active)nodes[p]=-1;continue;}
  for(int i=0;i<int(active.size());++i){labels[active[i]]=result[i]?a:b;nodes[active[i]]=-1;}
 }
 progress(1);
}
}
