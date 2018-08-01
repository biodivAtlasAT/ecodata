// Example Activity URL: https://biocollect.ala.org.au/actwaterwatch/bioActivity/create/b3efa2eb-ed40-4dc8-ae0a-0c9f0c25ab0e
// Activity Name: Rapid Appraisal of Riparian Condition (RARC)
// Output model name: rarc_EventAndLocationDetails
// jsMain: https://dl.dropbox.com/s/8tesww79r304hyj/overallQuality.js?dl=0
// Minified: https://dl.dropbox.com/s/svbt8rur6lr3z89/overallQuality.min.js?dl=0
self.data.proximityScore = ko.observable();
self.data.continuityScore = ko.observable();
self.data.habitatScoreTotal = ko.observable();
self.data.coverScoreTotal = ko.observable();
self.data.nativesScoreTotal = ko.observable();
self.data.debrisScoreTotal = ko.observable();
self.data.featuresScoreTotal = ko.observable();
self.data.totalScoreAggregate = ko.observable();
self.data.overallQuality = ko.observable();
self.transients.proximityScore = ko.computed(function() {
    var v = self.data.nearestNativeVegetationGreaterThan10ha();
    var proximityScore = 0;
    switch (v) {
        case "200 m - 1 km":
            proximityScore =  1;
            break;
        case "Contiguous":
            proximityScore =  2;
            break;
        case "Contiguous with patch > 50 ha":
            proximityScore =  3;
            break;
        default:
            proximityScore =  0;
    }
    self.data.proximityScore(proximityScore);
    return proximityScore;
}, this);
self.transients.continuityScore = ko.computed(function() {
    var v = self.data.proportionOfRiverBankWithVegetationGreaterThan5mDeep();
    var d = self.data.numberOfDiscontinuitiesGreaterThan50m() * 0.5;
    var continuityScore = 0;
    switch (v) {
        case "50 - 64%":
            continuityScore = d >= 1? 0: (1 - d).toFixed(2);
            break;
        case "65 - 79%":
            continuityScore = d >= 2? 0: (2 - d).toFixed(2);
            break;
        case "80 - 94%":
            continuityScore = d >= 3? 0: (3 - d).toFixed(2);
            break;
        case "> 95%":
            continuityScore = d >= 4? 0: (4 - d).toFixed(2);
            break;
        default:
            continuityScore = 0;
    }
    self.data.continuityScore(continuityScore);
    return continuityScore;
}, this);
self.transients.habitatScoreTotal = ko.computed(function(){
    var a = self.data.continuityScore() ?  self.data.continuityScore() : 0;
    var b = self.transients.tempCanopyVegetationTableScore() ? self.transients.tempCanopyVegetationTableScore() : 0;
    var c = self.data.proximityScore() ? self.data.proximityScore() : 0;
    var total = Number(a) + Number(b) + Number(c);
    self.data.habitatScoreTotal(total);
});
self.transients.featuresScoreTotal = ko.computed(function(){
    var total = 0;
    $.each(self.data.featuresTable(), function (i, object) {
        total = total + Number(object.featuresAverageScore());
    });
    self.data.featuresScoreTotal(total);
});

self.transients.debrisScoreTotal = ko.computed(function(){
    var total = 0;
    $.each(self.data.debrisTable(), function (i, object) {
        total = total + Number(object.vegetationWidthAverageScore());
    });
    self.data.debrisScoreTotal(total);
});

self.transients.coverScoreTotal = ko.computed(function(){
    var total = 0;
    $.each(self.data.vegetationCoverTable(), function (i, object) {
        total = total + Number(object.vegetationCoverAverageScore());
    });
    self.data.coverScoreTotal(total);
});

self.transients.totalScoreAggregate = ko.computed(function(){
    var total = 0;
    var d = Number(self.transients.debrisScoreTotal() ? self.transients.debrisScoreTotal() : 0);
    var f = Number(self.data.featuresScoreTotal() ? self.data.featuresScoreTotal() : 0);
    var h = Number(self.data.habitatScoreTotal() ? self.data.habitatScoreTotal() : 0);
    var c = Number(self.data.coverScoreTotal() ? self.data.coverScoreTotal() : 0);
    total = d + f + h + c;
    self.data.totalScoreAggregate(total);
});

self.transients.overallQuality = ko.computed(function(){
    var v = self.data.totalScoreAggregate();
    var quality = '';
    if (v <= 10) quality = "Degraded";
    if (v <= 20) quality = "Poor";
    if (v <= 30) quality = "Fair";
    quality = v <= 40? "Good": "Excellent";
    self.data.overallQuality(quality);
});