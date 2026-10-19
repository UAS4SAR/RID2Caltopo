#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>
namespace r2c::mapping {
// One independent vote per stereo pair. Sort before selecting support, so late
// evidence is never discarded and input order cannot change the chosen surface.
inline float supportedHeight(std::vector<float>& votes){
 votes.erase(std::remove_if(votes.begin(),votes.end(),[](float z){return !std::isfinite(z);}),votes.end());
 if(votes.size()<2)return std::numeric_limits<float>::quiet_NaN();
 std::sort(votes.begin(),votes.end());
 size_t end=0,bestCount=0;double sum=0,bestMean=0;float bestSpan=INFINITY;
 for(size_t start=0;start<votes.size();++start){
  while(end<votes.size()&&double(votes[end])-votes[start]<.65){sum+=votes[end++];}
  const size_t count=end-start;const double mean=sum/count;const float span=votes[end-1]-votes[start];
  if(count>bestCount){bestCount=count;bestMean=mean;bestSpan=span;}
  else if(count==bestCount){
   // This product represents the visible upper surface. When two separated
   // modes have equal support, retain the upper mode, not their midpoint and
   // not an artificial unknown cell. Overlapping modes prefer compact support.
   if(mean-bestMean>=.65 || (mean-bestMean<.65 && span<bestSpan)){bestSpan=span;bestMean=mean;}
  }
  sum-=votes[start];
 }
 // Require independent support even for the visible upper surface.
 if(bestCount<2)return std::numeric_limits<float>::quiet_NaN();
 return float(bestMean);
}
// Small in-memory fixture adapter. Production uses the disk-backed collector.
class SurfaceConsensus {
 std::vector<std::vector<float>> votes;
 public:
 explicit SurfaceConsensus(size_t size):votes(size){}
 void add(size_t index,float z){if(std::isfinite(z))votes.at(index).push_back(z);}
 float height(size_t index)const{auto copy=votes.at(index);return supportedHeight(copy);}
};
}
