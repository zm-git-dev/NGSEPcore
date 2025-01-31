package ngsep.assembly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ngsep.alignments.PairwiseAlignerDynamicKmers;
import ngsep.alignments.UngappedSearchHitsCluster;
import ngsep.alignments.UngappedSearchHitsClusterBuilder;
import ngsep.alignments.MinimizersTableReadAlignmentAlgorithm;
import ngsep.alignments.ReadAlignment;
import ngsep.sequences.UngappedSearchHit;
import ngsep.sequences.QualifiedSequence;

public class KmerHitsAssemblyEdgesFinder {

	private AssemblyGraph graph;
	
	private UngappedSearchHitsClusterBuilder clustersBuilder = new UngappedSearchHitsClusterBuilder();
	
	public static final int DEF_MIN_HITS = 50;
	
	private double minProportionOverlap = 0;
	
	private double minProportionEvidence = 0;
	
	private int idxDebug = -1;
	
	private boolean extensiveSearch = false;
	
	private boolean completeAlignment = false;
	
	
	
	public KmerHitsAssemblyEdgesFinder(AssemblyGraph graph) {
		this.graph = graph;
	}
	
	public AssemblyGraph getGraph() {
		return graph;
	}

	public double getMinProportionOverlap() {
		return minProportionOverlap;
	}

	public void setMinProportionOverlap(double minProportionOverlap) {
		this.minProportionOverlap = minProportionOverlap;
	}
	
	public boolean isExtensiveSearch() {
		return extensiveSearch;
	}

	public void setExtensiveSearch(boolean extensiveSearch) {
		this.extensiveSearch = extensiveSearch;
	}

	public boolean isCompleteAlignment() {
		return completeAlignment;
	}

	public void setCompleteAlignment(boolean completeAlignment) {
		this.completeAlignment = completeAlignment;
	}

	public List<AssemblySequencesRelationship> inferRelationshipsFromKmerHits(int queryIdx, CharSequence queryF, CharSequence queryR, Map<Integer, List<UngappedSearchHit>> hitsForward, Map<Integer, List<UngappedSearchHit>> hitsReverse, double compressionFactor ) {
		List<AssemblySequencesRelationship> relationships = new ArrayList<AssemblySequencesRelationship>();
		int queryLength = queryF.length();
		List<UngappedSearchHit> selfHits = hitsForward.get(queryIdx);
		int selfHitsCount = (selfHits!=null)?selfHits.size():0;
		
		int minHits = (int) Math.max(selfHitsCount*minProportionOverlap,DEF_MIN_HITS);
		List<UngappedSearchHitsCluster> queryClusters = (selfHits!=null)?clustersBuilder.clusterRegionKmerAlns(queryLength, queryLength, selfHits, 0):null;
		//int minHits = DEF_MIN_HITS;
		if(queryClusters==null || queryClusters.size()==0) {
			/*int maxHits = 0;
			for(Map.Entry<Integer, List<UngappedSearchHit>> entry: hitsForward.entrySet()) {
				if(entry.getKey()<queryIdx) maxHits = Math.max(maxHits, entry.getValue().size());
			}
			for(Map.Entry<Integer, List<UngappedSearchHit>> entry:hitsReverse.entrySet()) {
				if(entry.getKey()<queryIdx) maxHits = Math.max(maxHits, entry.getValue().size());
			}
			if (queryIdx == idxDebug) System.out.println("EdgesFinder. No self cluster. Query: "+queryIdx+" subject max hits: "+maxHits);
			minHits = (int) Math.max(minHits,0.05*maxHits);
			*/
		} else {
			Collections.sort(queryClusters, (o1,o2)-> o2.getNumDifferentKmers()-o1.getNumDifferentKmers());
			UngappedSearchHitsCluster cluster = queryClusters.get(0);
			AssemblyEdge edge = graph.getSameSequenceEdge(queryIdx);
			int [] alnData = PairwiseAlignerDynamicKmers.simulateAlignment(queryIdx, queryLength, queryIdx, queryLength, cluster);
			edge.setCoverageSharedKmers(alnData[0]);
			edge.setWeightedCoverageSharedKmers(alnData[1]);
			edge.setNumSharedKmers(cluster.getNumDifferentKmers());
			edge.setOverlapStandardDeviation((int) Math.round(cluster.getPredictedOverlapSD()));
			edge.setRawKmerHits(cluster.getRawKmerHits());
			edge.setRawKmerHitsSubjectStartSD((int)Math.round(cluster.getRawKmerHitsSubjectStartSD()));
			minHits = (int)Math.min(minHits, minProportionOverlap*cluster.getNumDifferentKmers());
			minHits = (int) Math.max(minHits,DEF_MIN_HITS);
			if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" self cluster hits: "+selfHitsCount+" self cluster kmers: "+cluster.getNumDifferentKmers()+" min hits: "+minHits);
		}
		if(!extensiveSearch) minHits*=2;
		if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" min hits: "+minHits);
		//Initial selection based on raw hit counts
		List<Integer> subjectIdxsF = filterAndSortSubjectIds(queryIdx, hitsForward, minHits);
		if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" Selected subject idxs forward: "+subjectIdxsF.size());
		List<Integer> subjectIdxsR = filterAndSortSubjectIds(queryIdx, hitsReverse, minHits);
		if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" Selected subject idxs reverse: "+subjectIdxsR.size());
		//List<AssemblySequencesRelationship> relationships = new ArrayList<AssemblySequencesRelationship>(subjectIdxsF.size()+subjectIdxsR.size());
		if(subjectIdxsF.size()==0 && subjectIdxsR.size()==0) {
			//System.out.println("Query "+queryIdx+" had zero subject ids after initial filtering. self hits: "+selfHitsCount+" min hits: "+minHits);
			return relationships;
		}
		
		if(extensiveSearch) {
			//Build initial clusters
			List<UngappedSearchHitsCluster> clustersForward = createClusters(queryIdx, queryLength, hitsForward, subjectIdxsF);
			if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" Clusters forward: "+clustersForward.size());
			List<UngappedSearchHitsCluster> clustersReverse = createClusters(queryIdx, queryLength, hitsReverse, subjectIdxsR);
			if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" Clusters reverse: "+clustersReverse.size());
			//Combined query min coverage and percentage of kmers
			int minClusterSize = calculateMinimumClusterSize(queryIdx, clustersForward, clustersReverse, minHits);
			for(UngappedSearchHitsCluster cluster:clustersForward) processCluster(queryIdx, queryF, false, cluster, compressionFactor, minClusterSize, relationships);
			for(UngappedSearchHitsCluster cluster:clustersReverse) processCluster(queryIdx, queryR, true, cluster, compressionFactor, minClusterSize, relationships);
		} else {
			int i=0;
			int minClusterSize = DEF_MIN_HITS;
			while(i<subjectIdxsF.size() && i<subjectIdxsR.size() && i<10) {
				int subjectIdxF = subjectIdxsF.get(i);
				List<UngappedSearchHitsCluster> subjectClustersF = createClusters(queryIdx, queryLength, subjectIdxF, hitsForward.get(subjectIdxF));
				for(UngappedSearchHitsCluster clusterF:subjectClustersF) {
					if (processCluster(queryIdx, queryF, false, clusterF, compressionFactor, minClusterSize, relationships)) return relationships;
					minClusterSize = Math.max(minClusterSize, clusterF.getNumDifferentKmers()/5);
				}
				int subjectIdxR = subjectIdxsR.get(i);
				List<UngappedSearchHitsCluster> subjectClustersR = createClusters(queryIdx, queryLength, subjectIdxR, hitsReverse.get(subjectIdxR));
				for(UngappedSearchHitsCluster clusterR:subjectClustersR) {
					if (processCluster(queryIdx, queryR, true, clusterR, compressionFactor, minClusterSize, relationships)) return relationships;
					minClusterSize = Math.max(minClusterSize, clusterR.getNumDifferentKmers()/5);
				}
				i++;
			}
			if(i==10) return relationships;
			for(;i<subjectIdxsF.size();i++) {
				int subjectIdxF = subjectIdxsF.get(i);
				List<UngappedSearchHitsCluster> subjectClusters = createClusters(queryIdx, queryLength, subjectIdxF, hitsForward.get(subjectIdxF));
				for(UngappedSearchHitsCluster cluster:subjectClusters) if(processCluster(queryIdx, queryF, false, cluster, compressionFactor, minClusterSize, relationships)) return relationships;
			}
			for(;i<subjectIdxsR.size();i++) {
				int subjectIdxR = subjectIdxsR.get(i);
				List<UngappedSearchHitsCluster> subjectClusters = createClusters(queryIdx, queryLength, subjectIdxR, hitsReverse.get(subjectIdxR));
				for(UngappedSearchHitsCluster cluster:subjectClusters) if(processCluster(queryIdx, queryR, true, cluster, compressionFactor, minClusterSize, relationships)) return relationships;
			}
		}
		return relationships;
	}

	private List<Integer> filterAndSortSubjectIds(int queryIdx, Map<Integer, List<UngappedSearchHit>> hits, int minHits) {
		List<Integer> subjectIdxs = new ArrayList<Integer>();
		Map<Integer,Integer> numQueryKmersSubject = new HashMap<Integer, Integer>();
		for(int subjectIdx:hits.keySet()) {
			if(subjectIdx>= queryIdx) continue;
			List<UngappedSearchHit> hitsSubject= hits.get(subjectIdx); 
			int subjectRawCount = hitsSubject.size();
			Set<Integer> distinctQueryIdxs = new HashSet<Integer>();
			for(UngappedSearchHit hit: hitsSubject) distinctQueryIdxs.add(hit.getQueryIdx());
			int numQueryKmers = distinctQueryIdxs.size();
			if (queryIdx == idxDebug && subjectRawCount>DEF_MIN_HITS) System.out.println("EdgesFinder. Query: "+queryIdx+" Subject sequence: "+subjectIdx+" hits: "+subjectRawCount+" distinct kmers: "+numQueryKmers+" min hits: "+minHits);
			if(numQueryKmers<minHits) continue;
			numQueryKmersSubject.put(subjectIdx, numQueryKmers);
			subjectIdxs.add(subjectIdx);
		}
		Collections.sort(subjectIdxs, (s1,s2)->numQueryKmersSubject.get(s2)-numQueryKmersSubject.get(s1));
		return subjectIdxs;
	}
	
	private List<UngappedSearchHitsCluster> createClusters(int queryIdx, int queryLength, Map<Integer, List<UngappedSearchHit>> hitsMap, List<Integer> subjectIdxs) {
		List<UngappedSearchHitsCluster> clusters = new ArrayList<UngappedSearchHitsCluster>(subjectIdxs.size());
		for(int subjectIdx:subjectIdxs) {
			List<UngappedSearchHit> hits = hitsMap.get(subjectIdx);
			List<UngappedSearchHitsCluster> subjectClusters = createClusters(queryIdx, queryLength, subjectIdx, hits);
			clusters.addAll(subjectClusters);
		}
		return clusters;
	}
	private List<UngappedSearchHitsCluster> createClusters (int queryIdx, int queryLength, int subjectIdx, List<UngappedSearchHit> hits) {
		QualifiedSequence subjectSequence = graph.getSequence(subjectIdx);
		int subjectLength = subjectSequence.getLength();
		List<UngappedSearchHitsCluster> subjectClusters = clustersBuilder.clusterRegionKmerAlns(queryLength, subjectLength, hits, 0);
		List<UngappedSearchHitsCluster> answer = new ArrayList<UngappedSearchHitsCluster>(2);
		if(subjectClusters.size()==0) return answer;
		Collections.sort(subjectClusters, (o1,o2)-> o2.getNumDifferentKmers()-o1.getNumDifferentKmers());
		UngappedSearchHitsCluster bestCluster = subjectClusters.get(0);
		answer.add(bestCluster);
		int numKmers = bestCluster.getNumDifferentKmers();
		boolean queryBefore = bestCluster.getSubjectPredictedStart()<0;
		if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" "+graph.getSequence(queryIdx).getName()+" Subject: "+subjectIdx+" "+subjectSequence.getName()+" hits: "+hits.size()+" subject clusters: "+subjectClusters.size()+" hits best subject cluster: "+numKmers+" subjectId in cluster: "+bestCluster.getSubjectIdx());
		if(subjectClusters.size()==1) return answer;
		UngappedSearchHitsCluster secondCluster = subjectClusters.get(1);
		if(secondCluster.getNumDifferentKmers()>0.8*numKmers && queryBefore != secondCluster.getSubjectPredictedStart()<0) {
			answer.add(secondCluster);
			if (queryIdx == idxDebug) System.out.println("EdgesFinder. Adding second cluster. Query: "+queryIdx+" "+graph.getSequence(queryIdx).getName()+" Subject: "+subjectIdx+" "+subjectSequence.getName()+" hits second subject cluster: "+secondCluster.getNumDifferentKmers());
		}
		return answer;
	}
	private int calculateMinimumClusterSize(int queryIdx, List<UngappedSearchHitsCluster> clustersForward, List<UngappedSearchHitsCluster> clustersReverse, int minHits) {
		List<UngappedSearchHitsCluster> allClusters = new ArrayList<UngappedSearchHitsCluster>(clustersForward.size()+clustersReverse.size());
		allClusters.addAll(clustersForward);
		allClusters.addAll(clustersReverse);
		int maxCount = 0;
		int passCount = 0;
		for(UngappedSearchHitsCluster cluster:allClusters) {
			int count = cluster.getNumDifferentKmers();
			maxCount = Math.max(maxCount, count);
			if(count >=minHits) passCount++;
		}
		if (queryIdx == idxDebug) System.out.println("EdgesFinder. Min hits: "+minHits+" number of passing clusters: "+passCount+" max count: "+maxCount);
		return Math.max(minHits, maxCount/5);
	}
	public void printSubjectHits(List<UngappedSearchHit> subjectHits) {
		for(UngappedSearchHit hit:subjectHits) {
			System.out.println(hit.getQueryIdx()+" "+hit.getSequenceIdx()+":"+hit.getStart());
		}
		
	}

	private boolean processCluster(int querySequenceId, CharSequence query, boolean queryRC, UngappedSearchHitsCluster cluster, double compressionFactor, int minClusterSize, List<AssemblySequencesRelationship> relationships) {
		int queryLength = query.length();
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		if(querySequenceId==idxDebug) System.out.println("Processing cluster. Query: "+querySequenceId+" length: "+queryLength+ " subject: "+cluster.getSubjectIdx()+" length: "+subjectLength+" subject predicted "+cluster.getSubjectPredictedStart()+" - "+cluster.getSubjectPredictedEnd());
		if(!passFilters(querySequenceId, queryLength, minClusterSize, cluster)) {
			cluster.disposeHits();
			return false;
		}
		
		//Zero based limits
		int startSubject = cluster.getSubjectPredictedStart();
		int endSubject = cluster.getSubjectPredictedEnd();
		
		if(startSubject>=0 && endSubject<=subjectLength) {
			return addEmbedded(querySequenceId, query, queryRC, compressionFactor, cluster, relationships);
		} else if (startSubject>=0) {
			addQueryAfterSubjectEdge(querySequenceId, query, queryRC, compressionFactor, cluster, relationships);
		} else if (endSubject<=subjectLength) {
			addQueryBeforeSubjectEdge(querySequenceId, query, queryRC, compressionFactor, cluster, relationships);
		} else {
			// Similar sequences. Add possible embedded
			addEmbedded(querySequenceId, query, queryRC, compressionFactor, cluster, relationships);
		}
		cluster.disposeHits();
		return false;
	}
	private boolean passFilters (int querySequenceId, int queryLength, int minClusterSize, UngappedSearchHitsCluster cluster) {
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		double overlap = cluster.getPredictedOverlap();
		int queryEvidenceLength = cluster.getQueryEvidenceEnd()-cluster.getQueryEvidenceStart();
		int subjectEvidenceLength = cluster.getSubjectEvidenceEnd() - cluster.getSubjectEvidenceStart();
		if(querySequenceId==idxDebug) System.out.println("EdgesFinder. Evaluating cluster. kmers: "+cluster.getNumDifferentKmers()+" min "+minClusterSize+ " subject: "+cluster.getSubjectIdx()+" length: "+subjectLength+" subject predicted "+cluster.getSubjectPredictedStart()+" - "+cluster.getSubjectPredictedEnd());
		if(cluster.getNumDifferentKmers()<minClusterSize) return false;
		if(querySequenceId==idxDebug) System.out.println("EdgesFinder. Evaluating cluster. qlen "+queryLength+" QPred: "+cluster.getQueryPredictedStart()+" - "+cluster.getQueryPredictedEnd()+" QEv: "+cluster.getQueryEvidenceStart()+" - "+cluster.getQueryEvidenceEnd()+" subject len: "+subjectLength+" Subject: "+cluster.getSubjectIdx()+" sPred: "+cluster.getSubjectPredictedStart()+" - "+cluster.getSubjectPredictedEnd()+" sEv: "+cluster.getSubjectEvidenceStart()+" - "+cluster.getSubjectEvidenceEnd()+" overlap1 "+overlap+" overlap2: "+cluster.getPredictedOverlap() +" plain count: "+cluster.getNumDifferentKmers()+" weighted count: "+cluster.getWeightedCount()+" min: "+(minProportionOverlap*queryLength));
		if(overlap < minProportionOverlap*queryLength) return false;
		if(overlap < minProportionOverlap*subjectLength) return false;
		if(queryEvidenceLength < minProportionEvidence*overlap) return false;
		if(subjectEvidenceLength < minProportionEvidence*overlap) return false;
		
		return true;
	}
	private boolean addEmbedded(int querySequenceId, CharSequence query, boolean queryRC, double compressionFactor, UngappedSearchHitsCluster cluster, List<AssemblySequencesRelationship> relationships) {
		int startSubject = cluster.getSubjectPredictedStart();
		int endSubject = cluster.getSubjectPredictedEnd();
		int subjectSeqIdx = cluster.getSubjectIdx();
		int queryLength = query.length();
		QualifiedSequence subjectSequence = graph.getSequence(subjectSeqIdx);
		int subjectLength = subjectSequence.getLength();
		double proportionEvidence = cluster.getSubjectEvidenceEnd()-cluster.getSubjectEvidenceStart();
		proportionEvidence/=queryLength;
		
		ReadAlignment aln = null;
		int [] simulatedAlnData = null;
		if(querySequenceId==idxDebug) System.out.println("Candidate embedded: "+subjectSeqIdx+" "+subjectSequence.getName()+" propEv "+proportionEvidence);
		if(completeAlignment) {
			if(querySequenceId==idxDebug) System.out.println("Performing complete alignment for embedded candidate: "+querySequenceId+" "+graph.getSequence(querySequenceId).getName()+" subject: "+subjectSeqIdx+" "+subjectSequence.getName());
			MinimizersTableReadAlignmentAlgorithm aligner = new MinimizersTableReadAlignmentAlgorithm();
			aln = aligner.buildCompleteAlignment(subjectSeqIdx, graph.getSequence(subjectSeqIdx).getCharacters().toString(), query, cluster);
			if(aln==null) {
				System.err.println("Alignment could not be performed for embedded candidate. Query: "+graph.getSequence(querySequenceId).getName()+" subject: "+subjectSequence.getName());
				return false;
			}
			if(querySequenceId==idxDebug) System.out.println("Alignment limits subject: "+aln.getFirst()+" - "+aln.getLast()+" query: "+aln.getAlignedReadPosition(aln.getFirst())+" - "+aln.getAlignedReadPosition(aln.getLast())+" CIGAR: "+aln.getCigarString());
			int skipStart = aln.getSoftClipStart();
			int skipEnd = aln.getSoftClipEnd();
			if(aln.getFirst()-skipStart<0) {
				if(querySequenceId==idxDebug) System.out.println("The alignment start minus the skipped base pairs goes beyond the subject sequence. Aln first: "+aln.getFirst()+" skip: "+skipStart);
				addQueryBeforeSubjectEdge(querySequenceId, query, queryRC, compressionFactor, cluster ,relationships);
				return false;
			}
			if(aln.getLast()+skipEnd>subjectLength) {
				if(querySequenceId==idxDebug) System.out.println("The alignment end plus the skipped base pairs goes beyond the subject sequence. Aln end: "+aln.getLast()+" skip: "+skipEnd);
				addQueryAfterSubjectEdge(querySequenceId, query, queryRC, compressionFactor, cluster, relationships);
				return false;
			}
		} else {
			simulatedAlnData =  PairwiseAlignerDynamicKmers.simulateAlignment(subjectSeqIdx, subjectLength, querySequenceId, queryLength, cluster);
			if(simulatedAlnData[2]<20) {
				UngappedSearchHitsCluster clusterMod = addLocalHits(subjectSeqIdx, querySequenceId, cluster,graph.getSequence(subjectSeqIdx).getCharacters().toString(),query);
				if(clusterMod!=cluster) {
					cluster = clusterMod;
					simulatedAlnData =  PairwiseAlignerDynamicKmers.simulateAlignment(subjectSeqIdx, subjectLength, querySequenceId, queryLength, cluster);
				}
			}
		}
		AssemblyEmbedded embeddedEvent = new AssemblyEmbedded(querySequenceId, graph.getSequence(querySequenceId), queryRC, subjectSeqIdx, graph.getSequence(subjectSeqIdx), startSubject, endSubject);
		embeddedEvent.setNumSharedKmers(cluster.getNumDifferentKmers());
		embeddedEvent.setHostStartStandardDeviation((int) Math.round(cluster.getSubjectStartSD()));
		embeddedEvent.setRawKmerHits(cluster.getRawKmerHits());
		embeddedEvent.setRawKmerHitsSubjectStartSD((int)Math.round(cluster.getRawKmerHitsSubjectStartSD()));
		if(aln!=null) {
			embeddedEvent.setHostEvidenceStart(aln.getFirst()-1);
			embeddedEvent.setHostEvidenceEnd(aln.getLast());
			embeddedEvent.setSequenceEvidenceStart(aln.getAlignedReadPosition(aln.getFirst()));
			embeddedEvent.setSequenceEvidenceEnd(aln.getAlignedReadPosition(aln.getLast())+1);
			embeddedEvent.setNumMismatches(aln.getNumMismatches());
			embeddedEvent.setCoverageSharedKmers(aln.getCoverageSharedKmers());
			embeddedEvent.setWeightedCoverageSharedKmers(aln.getWeightedCoverageSharedKmers());
			embeddedEvent.setNumIndels(aln.getTotalLengthIndelCalls());
			embeddedEvent.setAliginment(aln);
		} else {
			embeddedEvent.setHostEvidenceStart(cluster.getSubjectEvidenceStart());
			embeddedEvent.setHostEvidenceEnd(cluster.getSubjectEvidenceEnd());
			embeddedEvent.setSequenceEvidenceStart(cluster.getQueryEvidenceStart());
			embeddedEvent.setSequenceEvidenceEnd(cluster.getQueryEvidenceEnd());
			embeddedEvent.setCoverageSharedKmers(simulatedAlnData[0]);
			embeddedEvent.setWeightedCoverageSharedKmers(simulatedAlnData[1]);
			embeddedEvent.setNumIndels(simulatedAlnData[2]);
		}
		if(queryRC) {
			int reversedStart = queryLength - embeddedEvent.getSequenceEvidenceEnd();
			int reversedEnd = queryLength - embeddedEvent.getSequenceEvidenceStart();
			embeddedEvent.setSequenceEvidenceStart(reversedStart);
			embeddedEvent.setSequenceEvidenceEnd(reversedEnd);
		}
		
		/*synchronized (graph) {
			graph.addEmbedded(embeddedEvent);
		}*/
		relationships.add(embeddedEvent);
		
		if (querySequenceId==idxDebug) System.out.println("Query: "+querySequenceId+" embedded in "+subjectSeqIdx+" proportion evidence: "+proportionEvidence);
		return embeddedEvent.getEvidenceProportion()>0.99 && embeddedEvent.getIndelsPerKbp()<10 && embeddedEvent.getWeightedCoverageSharedKmers()>0.5*embeddedEvent.getRead().getLength();
	}
	private void addQueryAfterSubjectEdge(int querySequenceId, CharSequence query, boolean queryRC, double compressionFactor, UngappedSearchHitsCluster cluster, List<AssemblySequencesRelationship> relationships) {
		int queryLength = graph.getSequenceLength(querySequenceId);
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		AssemblyVertex vertexSubject = graph.getVertex(subjectSeqIdx, false);
		AssemblyVertex vertexQuery = graph.getVertex(querySequenceId, !queryRC);
		int overlap = (int) ((double)cluster.getPredictedOverlap()/compressionFactor);
		double proportionEvidence = cluster.getSubjectEvidenceEnd()-cluster.getSubjectEvidenceStart();
		proportionEvidence/=(overlap+1);
		
		ReadAlignment aln = null;
		int [] simulatedAlnData = null;
		if(querySequenceId==idxDebug) System.out.println("Candidate edge: "+subjectSeqIdx+" "+graph.getSequence(subjectSeqIdx).getName()+" propEv "+proportionEvidence+" overlap: "+overlap+" qlen: "+queryLength+" prop: "+overlap/queryLength);
		if(completeAlignment) {
			MinimizersTableReadAlignmentAlgorithm aligner = new MinimizersTableReadAlignmentAlgorithm();
			aln = aligner.buildCompleteAlignment(subjectSeqIdx, graph.getSequence(subjectSeqIdx).getCharacters().toString(), query, cluster);
			if(aln==null) {
				System.err.println("Alignment could not be performed for edge candidate. Query: "+graph.getSequence(querySequenceId).getName()+" subject: "+graph.getSequence(subjectSeqIdx).getName());
				return;
			}	
			if(querySequenceId==idxDebug) System.out.println("Alignment limits subject: "+aln.getFirst()+" - "+aln.getLast()+" query: "+aln.getAlignedReadPosition(aln.getFirst())+" - "+aln.getAlignedReadPosition(aln.getLast())+" CIGAR: "+aln.getCigarString());
			if(aln.getFirst()-aln.getSoftClipStart()>0 && aln.getLast()+aln.getSoftClipEnd()<subjectLength) {
				if(querySequenceId==idxDebug) System.out.println("Sequence looks embedded after alignment. New predicted limits: "+(aln.getFirst()-aln.getSoftClipStart())+" - "+(aln.getLast()+aln.getSoftClipEnd()));
				addEmbedded(querySequenceId, query, queryRC, compressionFactor, cluster, relationships);
			}
			overlap = (int) (((double)(subjectLength-aln.getFirst()))/compressionFactor);
		} else {
			simulatedAlnData =  PairwiseAlignerDynamicKmers.simulateAlignment(subjectSeqIdx, subjectLength, querySequenceId, queryLength, cluster);
			if(simulatedAlnData[2]<20) {
				UngappedSearchHitsCluster clusterMod = addLocalHits(subjectSeqIdx, querySequenceId, cluster,graph.getSequence(subjectSeqIdx).getCharacters().toString(),query);
				if(clusterMod!=cluster) {
					cluster = clusterMod;
					simulatedAlnData =  PairwiseAlignerDynamicKmers.simulateAlignment(subjectSeqIdx, subjectLength, querySequenceId, queryLength, cluster);
				}
			}
		}
		
		AssemblyEdge edge = new AssemblyEdge(vertexSubject, vertexQuery, overlap);
		edge.setAverageOverlap((int) ((double)cluster.getAveragePredictedOverlap()/compressionFactor));
		edge.setMedianOverlap((int) ((double)cluster.getMedianPredictedOverlap()/compressionFactor));
		edge.setFromLimitsOverlap((int) ((double)cluster.getFromLimitsPredictedOverlap()/compressionFactor));
		edge.setNumSharedKmers(cluster.getNumDifferentKmers());
		edge.setOverlapStandardDeviation((int) Math.round(cluster.getPredictedOverlapSD()));
		edge.setRawKmerHits(cluster.getRawKmerHits());
		edge.setRawKmerHitsSubjectStartSD((int)Math.round(cluster.getRawKmerHitsSubjectStartSD()));
		
		if(aln!=null) {
			edge.setVertex1EvidenceStart(aln.getFirst()-1);
			edge.setVertex1EvidenceEnd(aln.getLast());
			edge.setVertex2EvidenceStart(aln.getAlignedReadPosition(aln.getFirst()));
			edge.setVertex2EvidenceEnd(aln.getAlignedReadPosition(aln.getLast())+1);
			edge.setNumMismatches(aln.getNumMismatches());
			edge.setCoverageSharedKmers(aln.getCoverageSharedKmers());
			edge.setWeightedCoverageSharedKmers(aln.getWeightedCoverageSharedKmers());
			edge.setNumIndels(aln.getTotalLengthIndelCalls());
		} else {
			edge.setVertex1EvidenceStart(cluster.getSubjectEvidenceStart());
			edge.setVertex1EvidenceEnd(cluster.getSubjectEvidenceEnd());
			edge.setVertex2EvidenceStart(cluster.getQueryEvidenceStart());
			edge.setVertex2EvidenceEnd(cluster.getQueryEvidenceEnd());
			
			edge.setCoverageSharedKmers(simulatedAlnData[0]);
			edge.setWeightedCoverageSharedKmers(simulatedAlnData[1]);
			edge.setNumIndels(simulatedAlnData[2]);
		}
		if(queryRC) {
			int reversedStart = queryLength - edge.getVertex2EvidenceEnd();
			int reversedEnd = queryLength - edge.getVertex2EvidenceStart();
			edge.setVertex2EvidenceStart(reversedStart);
			edge.setVertex2EvidenceEnd(reversedEnd);
		}
		
		/*synchronized (graph) {
			graph.addEdge(edge);
		}*/
		relationships.add(edge);
		if(querySequenceId==idxDebug) System.out.println("New edge: "+edge);
	}

	private void addQueryBeforeSubjectEdge(int querySequenceId, CharSequence query, boolean queryRC, double compressionFactor, UngappedSearchHitsCluster cluster, List<AssemblySequencesRelationship> relationships) {
		int queryLength = graph.getSequenceLength(querySequenceId);
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		AssemblyVertex vertexSubject = graph.getVertex(subjectSeqIdx, true);
		AssemblyVertex vertexQuery = graph.getVertex(querySequenceId, queryRC);
		int overlap = (int) ((double)cluster.getPredictedOverlap()/compressionFactor);
		double proportionEvidence = cluster.getSubjectEvidenceEnd()-cluster.getSubjectEvidenceStart();
		proportionEvidence/=(overlap+1);

		ReadAlignment aln = null;
		int [] simulatedAlnData = null;
		if(querySequenceId==idxDebug) System.out.println("Candidate edge: "+subjectSeqIdx+" "+graph.getSequence(subjectSeqIdx).getName()+" propEv "+proportionEvidence+" overlap: "+overlap+" qlen: "+queryLength+" prop: "+overlap/queryLength);
		if(completeAlignment) {
			MinimizersTableReadAlignmentAlgorithm aligner = new MinimizersTableReadAlignmentAlgorithm();
			aln = aligner.buildCompleteAlignment(subjectSeqIdx, graph.getSequence(subjectSeqIdx).getCharacters().toString(), query, cluster);
			if(aln==null) {
				System.err.println("Alignment could not be performed for edge candidate. Query: "+graph.getSequence(querySequenceId).getName()+" subject: "+graph.getSequence(subjectSeqIdx).getName());
				return;
			}
			if(querySequenceId==idxDebug) System.out.println("Alignment limits subject: "+aln.getFirst()+" - "+aln.getLast()+" query: "+aln.getAlignedReadPosition(aln.getFirst())+" - "+aln.getAlignedReadPosition(aln.getLast())+" CIGAR: "+aln.getCigarString());
			if(aln.getFirst()-aln.getSoftClipStart()>0 && aln.getLast()+aln.getSoftClipEnd()<subjectLength) {
				if(querySequenceId==idxDebug) System.out.println("Sequence looks embedded after alignment. New predicted limits: "+(aln.getFirst()-aln.getSoftClipStart())+" - "+(aln.getLast()+aln.getSoftClipEnd()));
				addEmbedded(querySequenceId, query, queryRC, compressionFactor, cluster, relationships);
			}
			overlap = (int) (((double)aln.getLast())/compressionFactor);
		} else {
			simulatedAlnData =  PairwiseAlignerDynamicKmers.simulateAlignment(subjectSeqIdx, subjectLength, querySequenceId, queryLength, cluster);
			if(simulatedAlnData[2]<20) {
				UngappedSearchHitsCluster clusterMod = addLocalHits(subjectSeqIdx, querySequenceId, cluster,graph.getSequence(subjectSeqIdx).getCharacters().toString(),query);
				if(clusterMod!=cluster) {
					cluster = clusterMod;
					simulatedAlnData =  PairwiseAlignerDynamicKmers.simulateAlignment(subjectSeqIdx, subjectLength, querySequenceId, queryLength, cluster);
				}
			}
		}
		AssemblyEdge edge = new AssemblyEdge(vertexQuery, vertexSubject, overlap);
		edge.setAverageOverlap((int) ((double)cluster.getAveragePredictedOverlap()/compressionFactor));
		edge.setMedianOverlap((int) ((double)cluster.getMedianPredictedOverlap()/compressionFactor));
		edge.setFromLimitsOverlap((int) ((double)cluster.getFromLimitsPredictedOverlap()/compressionFactor));
		
		edge.setNumSharedKmers(cluster.getNumDifferentKmers());
		edge.setOverlapStandardDeviation((int) Math.round(cluster.getPredictedOverlapSD()));
		edge.setRawKmerHits(cluster.getRawKmerHits());
		edge.setRawKmerHitsSubjectStartSD((int)Math.round(cluster.getRawKmerHitsSubjectStartSD()));
		
		if(aln!=null) {
			edge.setVertex1EvidenceStart(aln.getAlignedReadPosition(aln.getFirst()));
			edge.setVertex1EvidenceEnd(aln.getAlignedReadPosition(aln.getLast()));
			edge.setVertex2EvidenceStart(aln.getFirst());
			edge.setVertex2EvidenceEnd(aln.getLast());
			edge.setNumMismatches(aln.getNumMismatches());
			edge.setCoverageSharedKmers(aln.getCoverageSharedKmers());
			edge.setWeightedCoverageSharedKmers(aln.getWeightedCoverageSharedKmers());
			edge.setNumIndels(aln.getTotalLengthIndelCalls());
		} else {
			edge.setVertex1EvidenceStart(cluster.getQueryEvidenceStart());
			edge.setVertex1EvidenceEnd(cluster.getQueryEvidenceEnd());
			edge.setVertex2EvidenceStart(cluster.getSubjectEvidenceStart());
			edge.setVertex2EvidenceEnd(cluster.getSubjectEvidenceEnd());
			edge.setCoverageSharedKmers(simulatedAlnData[0]);
			edge.setWeightedCoverageSharedKmers(simulatedAlnData[1]);
			edge.setNumIndels(simulatedAlnData[2]);
		}
		if(queryRC) {
			int reversedStart = queryLength - edge.getVertex1EvidenceEnd();
			int reversedEnd = queryLength - edge.getVertex1EvidenceStart();
			edge.setVertex1EvidenceStart(reversedStart);
			edge.setVertex1EvidenceEnd(reversedEnd);
		}
		/*synchronized (graph) {
			graph.addEdge(edge);
		}*/
		relationships.add(edge);
		if(querySequenceId==idxDebug) System.out.println("New edge: "+edge);
	}
	private UngappedSearchHitsCluster addLocalHits(int subjectSeqId, int querySequenceId, UngappedSearchHitsCluster cluster, String subject, CharSequence query) {
		return cluster;
		/*List<UngappedSearchHit> hits = cluster.getHitsByQueryIdx();
		List<UngappedSearchHit> newHits = new ArrayList<UngappedSearchHit>();
		newHits.addAll(hits);
		int addedHits = 0;
		int subjectS = Math.max(0, cluster.getSubjectPredictedStart());
		int queryS = Math.max(0,cluster.getQueryPredictedStart());
		for(UngappedSearchHit hit: hits) {
			int subjectES = hit.getStart();
			int queryES = hit.getQueryIdx();
			int dS = subjectES-subjectS;
			int dQ = queryES-queryS;
			
			if(dS>100 && dQ>100 && Math.abs(dS-dQ)<100 && dS<0.5*subject.length() && dQ<0.5*query.length()) {
				//TODO: Use paramenter for kmer length
				Map<Long,Integer> kmersSS = KmersExtractor.extractLocallyUniqueKmerCodes(subject, 15, subjectS, subjectES);
				Map<Integer,Long> kmersQS = KmersExtractor.extractDNAKmerCodes(query, 15, queryS, queryES);
				
				for(Map.Entry<Integer, Long> kmerQ:kmersQS.entrySet()) {
					Integer subjectPos = kmersSS.get(kmerQ.getValue());
					if(subjectPos==null) continue;
					UngappedSearchHit newhit = new UngappedSearchHit(query.subSequence(kmerQ.getKey(), kmerQ.getKey()+15), subjectSeqId, subjectPos);
					newhit.setQueryIdx(kmerQ.getKey());
					newhit.setWeight(0.5);
					newHits.add(newhit);
					addedHits++;
				}
			}
			subjectS = subjectES+15;
			queryS = queryES+15;
		}
		
		int subjectE = Math.min(subject.length()-1, cluster.getSubjectPredictedEnd());
		int queryE = Math.min(query.length()-1,cluster.getQueryPredictedEnd());
		int dS = subjectE-subjectS;
		int dQ = queryE-queryS;
		if(dS>100 && dQ>100 && Math.abs(dS-dQ)<100 && dS<0.5*subject.length() && dQ<0.5*query.length()) {
			//TODO: Use paramenter for kmer length
			Map<Long,Integer> kmersSE = KmersExtractor.extractLocallyUniqueKmerCodes(subject, 15, subjectS, subjectE);
			Map<Integer,Long> kmersQE = KmersExtractor.extractDNAKmerCodes(query, 15, queryS, queryE);
			
			for(Map.Entry<Integer, Long> kmerQ:kmersQE.entrySet()) {
				Integer subjectPos = kmersSE.get(kmerQ.getValue());
				if(subjectPos==null) continue;
				UngappedSearchHit newhit = new UngappedSearchHit(query.subSequence(kmerQ.getKey(), kmerQ.getKey()+15), subjectSeqId, subjectPos);
				newhit.setQueryIdx(kmerQ.getKey());
				newhit.setWeight(0.5);
				newHits.add(newhit);
				addedHits++;
			}
		}
		if(addedHits==0) return cluster;
		List<KmerHitsCluster> clusters = KmerHitsCluster.clusterRegionKmerAlns(query.length(), subject.length(), newHits, 0);
		if(querySequenceId==idxDebug) System.out.println("Redefining boundaries of cluster between "+subjectSeqId+" and "+querySequenceId+" new hits: "+addedHits+" clusters: "+clusters.size());
		if(clusters.size()==0) return cluster;
		Collections.sort(clusters, (c1,c2)-> c2.getNumDifferentKmers()-c1.getNumDifferentKmers());
		return clusters.get(0);*/
	}
}
