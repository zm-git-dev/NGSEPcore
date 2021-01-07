package ngsep.assembly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ngsep.alignments.MinimizersTableReadAlignmentAlgorithm;
import ngsep.sequences.UngappedSearchHit;
import ngsep.sequences.KmerHitsCluster;
import ngsep.sequences.QualifiedSequence;

public class KmerHitsAssemblyEdgesFinder {

	private AssemblyGraph graph;
	
	public static final int DEF_MIN_HITS = 50;
	
	private double minProportionOverlap = 0.05;
	
	private double minProportionEvidence = 0;
	
	private long expectedAssemblyLength = 0;
	
	private int countRawHits = 0;
	private int countCompletedHits = 0;
	
	private int idxDebug = 15000;
	
	
	
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

	public int getCountRawHits() {
		return countRawHits;
	}

	public int getCountCompletedHits() {
		return countCompletedHits;
	}
	
	public long getExpectedAssemblyLength() {
		return expectedAssemblyLength;
	}

	public void setExpectedAssemblyLength(long expectedAssemblyLength) {
		this.expectedAssemblyLength = expectedAssemblyLength;
	}

	public void updateGraphWithKmerHitsMap(int queryIdx, int queryLength, Map<Integer, Long> queryCodesF, Map<Integer, Long> queryCodesR, Map<Integer, List<UngappedSearchHit>> hitsForward, Map<Integer, List<UngappedSearchHit>> hitsReverse, double compressionFactor ) {
		List<UngappedSearchHit> selfHits = hitsForward.get(queryIdx);
		int selfHitsCount = (selfHits!=null)?selfHits.size():0;
		
		int minHits = (int) Math.max(selfHitsCount*minProportionOverlap,DEF_MIN_HITS);
		List<KmerHitsCluster> queryClusters = (selfHits!=null)?KmerHitsCluster.clusterRegionKmerAlns(queryLength, queryLength, selfHits, 0):null;
		int kmersSelfCluster = 0;
		if(queryClusters==null) {
			System.err.println("WARN: Self hits for sequence: "+queryIdx+" not found");
		} else if(queryClusters.size()==0) {
			System.err.println("WARN: Self hits for sequence: "+queryIdx+" dd not make clusters");
		} else {
			Collections.sort(queryClusters, (o1,o2)-> o2.getNumDifferentKmers()-o1.getNumDifferentKmers());
			KmerHitsCluster cluster = queryClusters.get(0);
			AssemblyEdge edge = graph.getSameSequenceEdge(queryIdx);
			int [] alnData = MinimizersTableReadAlignmentAlgorithm.simulateAlignment(queryIdx, queryLength, queryIdx, queryLength, cluster);
			edge.setCoverageSharedKmers(alnData[0]);
			edge.setWeightedCoverageSharedKmers(alnData[1]);
			edge.setNumSharedKmers(cluster.getNumDifferentKmers());
			kmersSelfCluster = cluster.getNumDifferentKmers();
			edge.setOverlapStandardDeviation((int) Math.round(cluster.getPredictedOverlapSD()));
			edge.setRawKmerHits(cluster.getRawKmerHits());
			edge.setRawKmerHitsSubjectStartSD((int)Math.round(cluster.getRawKmerHitsSubjectStartSD()));
			minHits = (int)Math.min(minHits, minProportionOverlap*cluster.getNumDifferentKmers());
			minHits = (int) Math.max(minHits,DEF_MIN_HITS);
		}
		if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" self raw hits: "+selfHitsCount+" kmersSelfCluster: "+kmersSelfCluster+" min hits: "+minHits);
		//Initial selection based on raw hit counts
		List<Integer> subjectIdxsF = filterAndSortSubjectIds(queryIdx, hitsForward, minHits);
		if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" Selected subject idxs forward: "+subjectIdxsF.size());
		List<Integer> subjectIdxsR = filterAndSortSubjectIds(queryIdx, hitsReverse, minHits);
		if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" Selected subject idxs reverse: "+subjectIdxsR.size());
		
		if(subjectIdxsF.size()==0 && subjectIdxsR.size()==0) {
			//System.out.println("Query "+queryIdx+" had zero subject ids after initial filtering. self hits: "+selfHitsCount+" min hits: "+minHits);
			return;
		}
		long cumulativeReadDepth = graph.getCumulativeLength(queryIdx)/expectedAssemblyLength;
		if(cumulativeReadDepth<30) {
			//Build initial clusters
			List<KmerHitsCluster> clustersForward = createClusters(queryIdx, queryLength, hitsForward, subjectIdxsF);
			if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" Clusters forward: "+clustersForward.size());
			List<KmerHitsCluster> clustersReverse = createClusters(queryIdx, queryLength, hitsReverse, subjectIdxsR);
			if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" Clusters reverse: "+clustersReverse.size());
			//Combined query min coverage and percentage of kmers
			int minClusterSize = calculateMinimumClusterSize(queryIdx, clustersForward, clustersReverse, minHits);
			for(KmerHitsCluster cluster:clustersForward) processCluster(queryIdx, queryLength, false, cluster, compressionFactor, minClusterSize);
			for(KmerHitsCluster cluster:clustersReverse) processCluster(queryIdx, queryLength, true, cluster, compressionFactor, minClusterSize);
		} else {
			int i=0;
			int minClusterSize = DEF_MIN_HITS;
			while(i<subjectIdxsF.size() && i<subjectIdxsR.size()) {
				int subjectIdxF = subjectIdxsF.get(i);
				KmerHitsCluster clusterF = createCluster(queryIdx, queryLength, subjectIdxF, hitsForward.get(subjectIdxF));
				if(clusterF!=null) {
					if (processCluster(queryIdx, queryLength, false, clusterF, compressionFactor, minClusterSize)) return;
					minClusterSize = Math.max(minClusterSize, clusterF.getNumDifferentKmers()/5);
				}
				int subjectIdxR = subjectIdxsR.get(i);
				KmerHitsCluster clusterR = createCluster(queryIdx, queryLength, subjectIdxR, hitsReverse.get(subjectIdxR));
				if(clusterR!=null) {
					if (processCluster(queryIdx, queryLength, true, clusterR, compressionFactor, minClusterSize)) return;
					minClusterSize = Math.max(minClusterSize, clusterR.getNumDifferentKmers()/5);
				}
				i++;
			}
			for(;i<subjectIdxsF.size();i++) {
				int subjectIdxF = subjectIdxsF.get(i);
				KmerHitsCluster clusterF = createCluster(queryIdx, queryLength, subjectIdxF, hitsForward.get(subjectIdxF));
				if(clusterF!=null && processCluster(queryIdx, queryLength, false, clusterF, compressionFactor, minClusterSize)) return;
			}
			for(;i<subjectIdxsR.size();i++) {
				int subjectIdxR = subjectIdxsR.get(i);
				KmerHitsCluster clusterR = createCluster(queryIdx, queryLength, subjectIdxR, hitsReverse.get(subjectIdxR));
				if (clusterR!=null && processCluster(queryIdx, queryLength, true, clusterR, compressionFactor, minClusterSize)) return;
			}
		}
		
	}

	private List<Integer> filterAndSortSubjectIds(int queryIdx, Map<Integer, List<UngappedSearchHit>> hits, int minHits) {
		List<Integer> subjectIdxs = new ArrayList<Integer>();
		Map<Integer,Integer> rawHitsProportionSubjectLength = new HashMap<Integer, Integer>();
		for(int subjectIdx:hits.keySet()) {
			if(subjectIdx>= queryIdx) continue;
			int subjectCount = hits.get(subjectIdx).size();
			if (queryIdx == idxDebug && subjectCount>DEF_MIN_HITS) System.out.println("EdgesFinder. Query: "+queryIdx+" Subject sequence: "+subjectIdx+" hits: "+subjectCount+" min hits: "+minHits);
			if(subjectCount<minHits) continue;
			long rawHitsProp = subjectCount*1000;
			int subjectLength = graph.getSequenceLength(subjectIdx);
			rawHitsProp/=subjectLength;
			rawHitsProportionSubjectLength.put(subjectIdx, (int)rawHitsProp);
			subjectIdxs.add(subjectIdx);
		}
		Collections.sort(subjectIdxs, (s1,s2)->rawHitsProportionSubjectLength.get(s2)-rawHitsProportionSubjectLength.get(s1));
		return subjectIdxs;
	}
	
	private List<KmerHitsCluster> createClusters(int queryIdx, int queryLength, Map<Integer, List<UngappedSearchHit>> hitsMap, List<Integer> subjectIdxs) {
		List<KmerHitsCluster> clusters = new ArrayList<KmerHitsCluster>(subjectIdxs.size());
		for(int subjectIdx:subjectIdxs) {
			List<UngappedSearchHit> hits = hitsMap.get(subjectIdx);
			KmerHitsCluster subjectCluster = createCluster(queryIdx, queryLength, subjectIdx, hits);
			if(subjectCluster!=null) clusters.add(subjectCluster);
		}
		return clusters;
	}
	private KmerHitsCluster createCluster (int queryIdx, int queryLength, int subjectIdx, List<UngappedSearchHit> hits) {
		QualifiedSequence subjectSequence = graph.getSequence(subjectIdx);
		int subjectLength = subjectSequence.getLength();
		List<KmerHitsCluster> subjectClusters = KmerHitsCluster.clusterRegionKmerAlns(queryLength, subjectLength, hits, 0);
		if(subjectClusters.size()==0) return null;
		Collections.sort(subjectClusters, (o1,o2)-> o2.getNumDifferentKmers()-o1.getNumDifferentKmers());
		KmerHitsCluster subjectCluster = subjectClusters.get(0);
		int numKmers = subjectCluster.getNumDifferentKmers();
		if (queryIdx == idxDebug) System.out.println("EdgesFinder. Query: "+queryIdx+" name "+graph.getSequence(queryIdx).getName()+" Subject: "+subjectSequence.getName()+" hits: "+hits.size()+" subject clusters: "+subjectClusters.size()+" hits best subject cluster: "+numKmers);
		return subjectCluster;
	}
	private int calculateMinimumClusterSize(int queryIdx, List<KmerHitsCluster> clustersForward, List<KmerHitsCluster> clustersReverse, int minHits) {
		List<KmerHitsCluster> allClusters = new ArrayList<KmerHitsCluster>(clustersForward.size()+clustersReverse.size());
		allClusters.addAll(clustersForward);
		allClusters.addAll(clustersReverse);
		int maxCount = 0;
		int passCount = 0;
		for(KmerHitsCluster cluster:allClusters) {
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

	private boolean processCluster(int querySequenceId, int queryLength, boolean queryRC, KmerHitsCluster cluster, double compressionFactor, int minClusterSize) {
		if(!passFilters(querySequenceId, queryLength, minClusterSize, cluster)) {
			cluster.disposeHits();
			return false;
		}
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		//Zero based limits
		int startSubject = cluster.getSubjectPredictedStart();
		int endSubject = cluster.getSubjectPredictedEnd();
		if(querySequenceId==idxDebug) System.out.println("Processing cluster. Query: "+querySequenceId+" length: "+queryLength+ " subject: "+cluster.getSubjectIdx()+" length: "+subjectLength);
		if(startSubject>=0 && endSubject<=subjectLength) {
			return addEmbedded(querySequenceId, queryLength, queryRC, cluster);
		} else if (startSubject>=0) {
			addQueryAfterSubjectEdge(querySequenceId, queryRC, compressionFactor, cluster);
		} else if (endSubject<=subjectLength) {
			addQueryBeforeSubjectEdge(querySequenceId, queryRC, compressionFactor, cluster);
		} else {
			// Similar sequences. Add possible embedded
			addEmbedded(querySequenceId, queryLength, queryRC, cluster);
		}
		cluster.disposeHits();
		return false;
	}
	private boolean passFilters (int querySequenceId, int queryLength, int minClusterSize, KmerHitsCluster cluster) {
		if(cluster.getNumDifferentKmers()<minClusterSize) return false;
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		double overlap = cluster.getPredictedOverlap();
		int queryEvidenceLength = cluster.getQueryEvidenceEnd()-cluster.getQueryEvidenceStart();
		int subjectEvidenceLength = cluster.getSubjectEvidenceEnd() - cluster.getSubjectEvidenceStart();
		//if(querySequenceId==idxDebug) System.out.println("EdgesFinder. Evaluating cluster. qlen "+queryLength+" QPred: "+cluster.getQueryPredictedStart()+" - "+cluster.getQueryPredictedEnd()+" QEv: "+cluster.getQueryEvidenceStart()+" - "+cluster.getQueryEvidenceEnd()+" subject len: "+subjectLength+" Subject: "+cluster.getSequenceIdx()+" sPred: "+cluster.getSubjectPredictedStart()+" - "+cluster.getSubjectPredictedEnd()+" sEv: "+cluster.getSubjectEvidenceStart()+" - "+cluster.getSubjectEvidenceEnd()+" overlap1 "+overlap+" overlap2: "+cluster.getPredictedOverlap() +" plain count: "+cluster.getNumDifferentKmers()+" weighted count: "+cluster.getWeightedCount()+" pct: "+pct);
		if(overlap < minProportionOverlap*queryLength) return false;
		if(overlap < minProportionOverlap*subjectLength) return false;
		if(queryEvidenceLength < minProportionEvidence*overlap) return false;
		if(subjectEvidenceLength < minProportionEvidence*overlap) return false;
		
		return true;
	}
	private boolean addEmbedded(int querySequenceId, int queryLength, boolean queryRC, KmerHitsCluster cluster) {
		int startSubject = cluster.getSubjectPredictedStart();
		int endSubject = cluster.getSubjectPredictedEnd();
		int subjectSeqIdx = cluster.getSubjectIdx();
		QualifiedSequence subjectSequence = graph.getSequence(subjectSeqIdx);
		int subjectLength = subjectSequence.getLength();
		AssemblyEmbedded embeddedEvent = new AssemblyEmbedded(querySequenceId, graph.getSequence(querySequenceId), queryRC, subjectSeqIdx, startSubject, endSubject);
		embeddedEvent.setHostEvidenceStart(cluster.getSubjectEvidenceStart());
		embeddedEvent.setHostEvidenceEnd(cluster.getSubjectEvidenceEnd());
		embeddedEvent.setSequenceEvidenceStart(cluster.getQueryEvidenceStart());
		embeddedEvent.setSequenceEvidenceEnd(cluster.getQueryEvidenceEnd());
		embeddedEvent.setNumSharedKmers(cluster.getNumDifferentKmers());
		embeddedEvent.setHostStartStandardDeviation((int) Math.round(cluster.getSubjectStartSD()));
		int [] alnData = MinimizersTableReadAlignmentAlgorithm.simulateAlignment(subjectSeqIdx, subjectLength, querySequenceId, queryLength, cluster);
		embeddedEvent.setCoverageSharedKmers(alnData[0]);
		embeddedEvent.setWeightedCoverageSharedKmers(alnData[1]);
		embeddedEvent.setRawKmerHits(cluster.getRawKmerHits());
		embeddedEvent.setRawKmerHitsSubjectStartSD((int)Math.round(cluster.getRawKmerHitsSubjectStartSD()));
		synchronized (graph) {
			graph.addEmbedded(embeddedEvent);
		}
		double proportionEvidence = cluster.getSubjectEvidenceEnd()-cluster.getSubjectEvidenceStart();
		proportionEvidence/=queryLength;
		if (querySequenceId==idxDebug) System.out.println("Query: "+querySequenceId+" embedded in "+subjectSeqIdx+" proportion evidence: "+proportionEvidence);
		return proportionEvidence>0.95;
	}
	private void addQueryAfterSubjectEdge(int querySequenceId, boolean queryRC, double compressionFactor, KmerHitsCluster cluster) {
		int queryLength = graph.getSequenceLength(querySequenceId);
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		AssemblyVertex vertexSubject = graph.getVertex(subjectSeqIdx, false);
		AssemblyVertex vertexQuery = graph.getVertex(querySequenceId, !queryRC);
		int overlap = (int) ((double)cluster.getPredictedOverlap()/compressionFactor);
		AssemblyEdge edge = new AssemblyEdge(vertexSubject, vertexQuery, overlap);
		edge.setAverageOverlap((int) ((double)cluster.getAveragePredictedOverlap()/compressionFactor));
		edge.setMedianOverlap((int) ((double)cluster.getMedianPredictedOverlap()/compressionFactor));
		edge.setFromLimitsOverlap((int) ((double)cluster.getFromLimitsPredictedOverlap()/compressionFactor));
		//ReadAlignment aln = aligner.buildCompleteAlignment(subjectSeqIdx, graph.getSequence(subjectSeqIdx).getCharacters(), query, cluster);
		//int mismatches = overlap;
		//if(aln!=null) mismatches = aln.getNumMismatches();
		int [] alnData = MinimizersTableReadAlignmentAlgorithm.simulateAlignment(subjectSeqIdx, subjectLength, querySequenceId, queryLength, cluster);
		edge.setCoverageSharedKmers(alnData[0]);
		edge.setWeightedCoverageSharedKmers(alnData[1]);
		edge.setNumSharedKmers(cluster.getNumDifferentKmers());
		edge.setOverlapStandardDeviation((int) Math.round(cluster.getPredictedOverlapSD()));
		edge.setRawKmerHits(cluster.getRawKmerHits());
		edge.setRawKmerHitsSubjectStartSD((int)Math.round(cluster.getRawKmerHitsSubjectStartSD()));
		edge.setVertex1EvidenceStart(cluster.getSubjectEvidenceStart());
		edge.setVertex1EvidenceEnd(cluster.getSubjectEvidenceEnd());
		edge.setVertex2EvidenceStart(cluster.getQueryEvidenceStart());
		edge.setVertex2EvidenceEnd(cluster.getQueryEvidenceEnd());
		synchronized (graph) {
			graph.addEdge(edge);
		}
		if(querySequenceId==idxDebug) System.out.println("New edge: "+edge);
	}
	private void addQueryBeforeSubjectEdge(int querySequenceId, boolean queryRC, double compressionFactor, KmerHitsCluster cluster) {
		int queryLength = graph.getSequenceLength(querySequenceId);
		int subjectSeqIdx = cluster.getSubjectIdx();
		int subjectLength = graph.getSequenceLength(subjectSeqIdx);
		AssemblyVertex vertexSubject = graph.getVertex(subjectSeqIdx, true);
		AssemblyVertex vertexQuery = graph.getVertex(querySequenceId, queryRC);
		int overlap = (int) ((double)cluster.getPredictedOverlap()/compressionFactor);
		AssemblyEdge edge = new AssemblyEdge(vertexQuery, vertexSubject, overlap);
		edge.setAverageOverlap((int) ((double)cluster.getAveragePredictedOverlap()/compressionFactor));
		edge.setMedianOverlap((int) ((double)cluster.getMedianPredictedOverlap()/compressionFactor));
		edge.setFromLimitsOverlap((int) ((double)cluster.getFromLimitsPredictedOverlap()/compressionFactor));
		//ReadAlignment aln = aligner.buildCompleteAlignment(subjectSeqIdx, graph.getSequence(subjectSeqIdx).getCharacters(), query, cluster);
		//int mismatches = overlap;
		//if(aln!=null) mismatches = aln.getNumMismatches();
		int [] alnData = MinimizersTableReadAlignmentAlgorithm.simulateAlignment(subjectSeqIdx, subjectLength, querySequenceId, queryLength, cluster);
		edge.setCoverageSharedKmers(alnData[0]);
		edge.setWeightedCoverageSharedKmers(alnData[1]);
		edge.setNumSharedKmers(cluster.getNumDifferentKmers());
		edge.setOverlapStandardDeviation((int) Math.round(cluster.getPredictedOverlapSD()));
		edge.setRawKmerHits(cluster.getRawKmerHits());
		edge.setRawKmerHitsSubjectStartSD((int)Math.round(cluster.getRawKmerHitsSubjectStartSD()));
		edge.setVertex1EvidenceStart(cluster.getQueryEvidenceStart());
		edge.setVertex1EvidenceEnd(cluster.getQueryEvidenceEnd());
		edge.setVertex2EvidenceStart(cluster.getSubjectEvidenceStart());
		edge.setVertex2EvidenceEnd(cluster.getSubjectEvidenceEnd());
		synchronized (graph) {
			graph.addEdge(edge);
		}
		if(querySequenceId==idxDebug) System.out.println("New edge: "+edge);
	}
}
